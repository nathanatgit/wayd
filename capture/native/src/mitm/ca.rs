// SPDX-License-Identifier: GPL-3.0-or-later
//! Local certificate authority: generation, leaf issuing, and the Android system-store file name.

use md5::{Digest, Md5};
use rcgen::{BasicConstraints, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair, KeyUsagePurpose};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use time::{Duration, OffsetDateTime};

pub struct CaPem {
    pub cert_pem: String,
    pub key_pem: String,
}

/// New self-signed CA (EC P-256, 1 year, path length 0). `common_name` is shown in the Android certificate UI.
pub fn generate_ca(common_name: &str) -> Result<CaPem, String> {
    let key = KeyPair::generate().map_err(|e| e.to_string())?;
    let mut params = CertificateParams::default();
    params.distinguished_name.push(DnType::CommonName, common_name);
    params.distinguished_name.push(DnType::OrganizationName, "WAYD");
    params.is_ca = IsCa::Ca(BasicConstraints::Constrained(0));
    params.key_usages = vec![KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
    let now = OffsetDateTime::now_utc();
    params.not_before = now - Duration::days(1);
    params.not_after = now + Duration::days(365);
    let cert = params.self_signed(&key).map_err(|e| e.to_string())?;
    Ok(CaPem { cert_pem: cert.pem(), key_pem: key.serialize_pem() })
}

/// A loaded CA that can issue short-lived leaf certificates for hostnames.
pub struct Authority {
    issuer: Issuer<'static, KeyPair>,
    pub cert_der: CertificateDer<'static>,
}

impl Authority {
    pub fn from_pem(cert_pem: &str, key_pem: &str) -> Result<Self, String> {
        let key = KeyPair::from_pem(key_pem).map_err(|e| e.to_string())?;
        let issuer = Issuer::from_ca_cert_pem(cert_pem, key).map_err(|e| e.to_string())?;
        let der = pem_to_der(cert_pem)?;
        Ok(Authority { issuer, cert_der: CertificateDer::from(der) })
    }

    /// Leaf for `host` (DNS name or IP), valid 30 days, server-auth only. Returns (certificate, PKCS#8 key).
    pub fn issue_leaf(&self, host: &str) -> Result<(CertificateDer<'static>, PrivateKeyDer<'static>), String> {
        let mut params = CertificateParams::new(vec![host.to_string()]).map_err(|e| e.to_string())?;
        params.distinguished_name.push(DnType::CommonName, host);
        params.is_ca = IsCa::NoCa;
        params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
        params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
        let now = OffsetDateTime::now_utc();
        params.not_before = now - Duration::days(1);
        params.not_after = now + Duration::days(30);
        let key = KeyPair::generate().map_err(|e| e.to_string())?;
        let cert = params.signed_by(&key, &self.issuer).map_err(|e| e.to_string())?;
        let key_der = PrivatePkcs8KeyDer::from(key.serialize_der());
        Ok((cert.der().clone(), PrivateKeyDer::Pkcs8(key_der)))
    }
}

fn pem_to_der(pem: &str) -> Result<Vec<u8>, String> {
    let mut reader = std::io::BufReader::new(pem.as_bytes());
    let first = rustls_pemfile::certs(&mut reader).next();
    match first {
        Some(Ok(c)) => Ok(c.to_vec()),
        Some(Err(e)) => Err(e.to_string()),
        None => Err("no certificate in PEM".to_string()),
    }
}

/// OpenSSL `subject_hash_old` of the certificate: the file name (`<hash>.0`) Android's system trust store expects.
/// It is the first four bytes (little endian) of the MD5 of the DER-encoded subject name.
pub fn subject_hash_old(cert_pem: &str) -> Result<String, String> {
    let der = pem_to_der(cert_pem)?;
    let subject = subject_der(&der).ok_or_else(|| "cannot parse certificate".to_string())?;
    let digest = Md5::digest(subject);
    let v = u32::from_le_bytes([digest[0], digest[1], digest[2], digest[3]]);
    Ok(format!("{v:08x}"))
}

/// Walks the X.509 structure just far enough to return the subject Name (including its SEQUENCE header).
fn subject_der(cert: &[u8]) -> Option<&[u8]> {
    fn read(b: &[u8]) -> Option<(u8, &[u8], usize)> {
        let tag = *b.first()?;
        let l0 = *b.get(1)? as usize;
        let (len, hdr) = if l0 < 0x80 {
            (l0, 2)
        } else {
            let n = l0 & 0x7F;
            if n == 0 || n > 4 {
                return None;
            }
            let mut v = 0usize;
            for i in 0..n {
                v = (v << 8) | *b.get(2 + i)? as usize;
            }
            (v, 2 + n)
        };
        let end = hdr.checked_add(len)?;
        Some((tag, b.get(hdr..end)?, end))
    }
    let (_, cert_body, _) = read(cert)?;
    let (_, tbs, _) = read(cert_body)?;
    let mut rest = tbs;
    let (tag, _, used) = read(rest)?;
    if tag == 0xA0 {
        rest = &rest[used..]; // optional [0] version
    }
    for _ in 0..4 {
        // serialNumber, signature, issuer, validity
        let (_, _, used) = read(rest)?;
        rest = &rest[used..];
    }
    let (_, _, used) = read(rest)?;
    Some(&rest[..used]) // subject
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::client::WebPkiServerVerifier;
    use rustls::client::danger::ServerCertVerifier;
    use rustls::pki_types::{ServerName, UnixTime};
    use rustls::RootCertStore;
    use std::sync::Arc;

    #[test]
    fn leaf_chains_to_the_ca_and_matches_the_hostname() {
        let ca = generate_ca("WAYD Local CA - test").unwrap();
        let auth = Authority::from_pem(&ca.cert_pem, &ca.key_pem).unwrap();
        let (leaf, _key) = auth.issue_leaf("api.example.com").unwrap();

        let mut roots = RootCertStore::empty();
        roots.add(auth.cert_der.clone()).unwrap();
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let verifier = WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider).build().unwrap();
        let name = ServerName::try_from("api.example.com").unwrap();
        verifier.verify_server_cert(&leaf, &[], &name, &[], UnixTime::now()).expect("leaf must verify");
        let wrong = ServerName::try_from("other.example.org").unwrap();
        assert!(verifier.verify_server_cert(&leaf, &[], &wrong, &[], UnixTime::now()).is_err());
    }

    #[test]
    fn ip_literal_leaf_is_supported() {
        let ca = generate_ca("t").unwrap();
        let auth = Authority::from_pem(&ca.cert_pem, &ca.key_pem).unwrap();
        assert!(auth.issue_leaf("93.184.216.34").is_ok());
    }

    #[test]
    fn subject_hash_is_eight_hex_digits_and_stable() {
        let ca = generate_ca("WAYD Local CA - hash").unwrap();
        let h1 = subject_hash_old(&ca.cert_pem).unwrap();
        let h2 = subject_hash_old(&ca.cert_pem).unwrap();
        assert_eq!(h1.len(), 8);
        assert_eq!(h1, h2);
        assert!(h1.chars().all(|c| c.is_ascii_hexdigit()));
        std::fs::create_dir_all("target/mitm-test").unwrap();
        std::fs::write("target/mitm-test/ca.pem", &ca.cert_pem).unwrap();
        std::fs::write("target/mitm-test/hash.txt", &h1).unwrap();
    }

    #[test]
    fn garbage_pem_is_rejected() {
        assert!(Authority::from_pem("nope", "nope").is_err());
        assert!(subject_hash_old("nope").is_err());
    }
}
