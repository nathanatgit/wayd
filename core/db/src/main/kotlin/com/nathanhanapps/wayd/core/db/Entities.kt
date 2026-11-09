package com.nathanhanapps.wayd.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.BlockScope
import com.nathanhanapps.wayd.core.model.CaptureSource
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.NetType
import com.nathanhanapps.wayd.core.model.Privilege
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TierSource
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.core.model.UserState

// Mirrors docs/02-DATA-MODEL.md. Change both together.

@Entity
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    val privilege: Privilege,
    val coverageJson: String,
)

@Entity(indices = [Index("sessionId"), Index("pkg"), Index("host"), Index("startAt")])
data class Flow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val uid: Int,
    val pkg: String,
    val userId: Int,
    val proto: Proto,
    val ipVersion: Int,
    val dstIp: String,
    val dstPort: Int,
    val host: String?,
    val hostSource: HostSource?,
    val bytesUp: Long,
    val bytesDown: Long,
    val pktsUp: Int,
    val pktsDown: Int,
    val startAt: Long,
    val endAt: Long?,
    val screenOn: Boolean,
    val appForeground: Boolean,
    val netType: NetType,
    val tlsVersion: String?,
    val alpn: String?,
    val ja4: String?,
    val country: String?,
    val asn: String?,
    val company: String?,
    val category: TrackerCategory?,
    val source: CaptureSource,
)

@Entity(indices = [Index("flowId")])
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: Long,
    val dir: Dir,
    val ts: Long,
    val tier: Tier,
    val tierSource: TierSource?,
    val method: String?,
    val url: String?,
    val status: Int?,
    val headersJson: String?,
    val bodyBlobId: String?,
    val bodySize: Int,
    val contentType: String?,
    /** Captured body prefix. The whole database is SQLCipher-encrypted, so no extra blob layer is used (D-023). */
    val body: ByteArray? = null,
    @ColumnInfo(defaultValue = "0") val bodyTruncated: Boolean = false,
) {
    override fun equals(other: Any?) = other is Message && id == other.id && flowId == other.flowId && ts == other.ts && dir == other.dir
    override fun hashCode() = 31 * id.hashCode() + flowId.hashCode()
}

/** Apps whose TLS traffic WAYD may decrypt (research flavor; empty unless the user adds apps after consenting). */
@Entity
data class InterceptApp(@PrimaryKey val pkg: String, val addedAt: Long)

@Entity
data class DnsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val uid: Int,
    val ts: Long,
    val qname: String,
    val qtype: Int,
    val answersJson: String,
    val viaDoh: Boolean,
)

/**
 * One permission/op use record. For the AppOps source this is one history *bucket* (about 15 minutes for recent data):
 * [ts] is the bucket start, [bucketEnd] its end and [count] how many accesses happened in it.
 * [source] = "appops:" plus the process state (top, fgsvc, fg, bg, cch, pers); [background] is derived from the state.
 */
@Entity(indices = [Index("pkg"), Index("ts")])
data class PermEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val op: String,
    val ts: Long,
    val durationMs: Long?,
    val background: Boolean,
    val source: String,
    @ColumnInfo(defaultValue = "1") val count: Int = 1,
    @ColumnInfo(defaultValue = "0") val bucketEnd: Long = 0,
)

@Entity
data class AlarmInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val tag: String,
    val type: String,
    val repeatMs: Long?,
    val seenAt: Long,
)

@Entity
data class AppInfo(
    @PrimaryKey val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String?,
    val signerSha256: String?,
    val signerSubject: String?,
    val installer: String?,
    val firstSeen: Long,
    val lastScanned: Long?,
)

@Entity
data class ApkFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val versionCode: Long,
    val kind: ApkFactKind,
    val key: String,
    val value: String?,
    val evidence: String?,
)

@Entity
data class Finding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val pkg: String,
    val ruleId: String,
    val severity: Severity,
    val title: String,
    val dataTypes: String,
    val evidenceJson: String,
    val createdAt: Long,
    val userState: UserState,
)

@Entity
data class AiAnalysis(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputRefsJson: String,
    val model: String,
    val promptVersion: Int,
    val resultJson: String,
    val validated: Boolean,
    val createdAt: Long,
    val durationMs: Long,
)

@Entity
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: BlockScope,
    val pkg: String?,
    val host: String?,
    val enabled: Boolean,
    val createdAt: Long,
)

@Entity
data class ConsentRecord(
    @PrimaryKey val feature: String,
    val statementVersion: Int,
    val acceptedAt: Long,
    val revokedAt: Long?,
)
