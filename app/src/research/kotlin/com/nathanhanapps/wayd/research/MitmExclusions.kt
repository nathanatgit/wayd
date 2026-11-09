package com.nathanhanapps.wayd.research

/**
 * Apps and hosts that are never decrypted (docs/08): banking, payment, government, password managers, OS updates
 * and app stores. Maintained in code on purpose; the user cannot add these apps to the picker.
 */
object MitmExclusions {
    private val packageExact = setOf(
        "com.eg.android.AlipayGphone", "com.unionpay", "com.tencent.mobileqq.pay", "com.paypal.android.p2pmobile",
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay",
        "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.agilebits.onepassword", "com.lastpass.lpandroid",
        "com.keepassdroid", "org.keepassxc", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.providers.downloads",
        "com.android.shell", "android", "com.android.systemui", "com.android.settings",
        "com.android.chrome.updater", "com.huawei.appmarket", "com.xiaomi.market", "com.heytap.market",
        "com.bbk.appstore", "com.tencent.android.qqdownloader", "com.coolapk.market", "com.nathanhanapps.wayd",
        "com.nathanhanapps.wayd.research", "moe.shizuku.privileged.api",
    )
    private val packageContains = listOf(
        "bank", "icbc", "ccb.", "abchina", "cmbchina", "bocom", "pingan", "citic", "boc.", "gov.cn", ".gov.",
        "alipay", "unionpay", "wallet", "passwordmanager", "keepass", "authenticator",
    )

    /** Host suffixes never intercepted, regardless of which app asks. */
    val hostSuffixes = listOf(
        "alipay.com", "alipayobjects.com", "antgroup.com", "unionpay.com", "95516.com", "icbc.com.cn", "ccb.com",
        "abchina.com", "cmbchina.com", "bankcomm.com", "boc.cn", "pingan.com", "citicbank.com", "paypal.com",
        "stripe.com", "gov.cn", "bitwarden.com", "1password.com", "lastpass.com", "wallet.google.com",
        "play.googleapis.com", "android.clients.google.com", "android.googleapis.com", "update.googleapis.com",
        "gvt1.com", "gvt2.com", "ota.nubia.com", "huggingface.co",
    )

    fun isExcludedPackage(pkg: String): Boolean {
        if (pkg in packageExact) return true
        val p = pkg.lowercase()
        return packageContains.any { it in p }
    }
}
