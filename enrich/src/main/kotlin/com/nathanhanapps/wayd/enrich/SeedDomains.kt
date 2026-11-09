package com.nathanhanapps.wayd.enrich

import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.core.model.TrackerCategory.ADS
import com.nathanhanapps.wayd.core.model.TrackerCategory.ANALYTICS
import com.nathanhanapps.wayd.core.model.TrackerCategory.CARRIER
import com.nathanhanapps.wayd.core.model.TrackerCategory.CDN
import com.nathanhanapps.wayd.core.model.TrackerCategory.CRASH
import com.nathanhanapps.wayd.core.model.TrackerCategory.OS
import com.nathanhanapps.wayd.core.model.TrackerCategory.PUSH
import com.nathanhanapps.wayd.core.model.TrackerCategory.UNKNOWN

data class SeedEntry(val domain: String, val company: String, val category: TrackerCategory)

/**
 * Hand-curated seed of well-known domains. Facts only (owner of a public domain); category is a best-effort label for
 * the domain's *primary* purpose. Specific subdomains are listed before their parent where the purpose differs.
 */
object SeedDomains {
    private fun e(company: String, category: TrackerCategory, vararg domains: String) =
        domains.map { SeedEntry(it, company, category) }

    val all: List<SeedEntry> = buildList {
        // ByteDance
        addAll(e("ByteDance", ADS, "pangolin-sdk-toutiao.com", "pglstatp-toutiao.com", "pangle.io", "pangleglobal.com"))
        addAll(e("ByteDance", ANALYTICS, "log.snssdk.com", "mon.snssdk.com", "applog.zijieapi.com", "volces.com", "bytedance.com"))
        addAll(e("ByteDance", CDN, "byteimg.com", "pstatp.com", "douyinpic.com", "douyinvod.com", "douyincdn.com", "bytecdn.cn", "ibytedtos.com", "tiktokcdn.com"))
        addAll(e("ByteDance", UNKNOWN, "douyin.com", "snssdk.com", "toutiao.com", "ixigua.com", "amemv.com", "zijieapi.com", "tiktokv.com", "tiktok.com", "bytedns.net", "feishu.cn", "larksuite.com"))
        // Tencent
        addAll(e("Tencent", ADS, "gdt.qq.com", "e.qq.com", "adsmind.gdtimg.com"))
        addAll(e("Tencent", ANALYTICS, "pingtcss.qq.com", "btrace.qq.com", "stat.qq.com", "ta.qq.com", "mta.qq.com"))
        addAll(e("Tencent", CRASH, "bugly.qq.com", "android.bugly.qq.com", "rqd.qq.com"))
        addAll(e("Tencent", CDN, "gtimg.com", "qpic.cn", "idqqimg.com", "myqcloud.com", "qcloud.com", "tencent-cloud.com", "qlogo.cn"))
        addAll(e("Tencent", UNKNOWN, "qq.com", "weixin.qq.com", "wechat.com", "tencent.com", "tenpay.com", "weixinbridge.com", "weiyun.com"))
        // Alibaba
        addAll(e("Alibaba", ANALYTICS, "umeng.com", "umengcloud.com", "umsns.com", "cnzz.com", "mmstat.com"))
        addAll(e("Alibaba", ADS, "alimama.com", "tanx.com", "atm.youku.com"))
        addAll(e("Alibaba", PUSH, "agoo.taobao.com", "accs.taobao.com"))
        addAll(e("Alibaba", CDN, "alicdn.com", "aliyuncs.com", "alikunlun.com", "alipayobjects.com", "tbcdn.cn", "aliyun.com", "alibabadns.com"))
        addAll(e("Alibaba", UNKNOWN, "alibaba.com", "taobao.com", "tmall.com", "alipay.com", "1688.com", "dingtalk.com", "amap.com", "autonavi.com", "youku.com", "xiami.com", "ele.me", "fliggy.com"))
        // Baidu
        addAll(e("Baidu", ANALYTICS, "hm.baidu.com", "mobads-logs.baidu.com", "pos.baidu.com", "cpro.baidu.com"))
        addAll(e("Baidu", ADS, "mobads.baidu.com", "cpro.baidustatic.com", "eclick.baidu.com"))
        addAll(e("Baidu", CDN, "bdstatic.com", "baidustatic.com", "bcebos.com", "bdimg.com", "baidupcs.com"))
        addAll(e("Baidu", UNKNOWN, "baidu.com", "baidubce.com", "bdydns.com", "hao123.com", "tieba.com"))
        // Xiaomi
        addAll(e("Xiaomi", ADS, "ad.xiaomi.com", "ad.mi.com", "adv.sec.miui.com", "api.ad.xiaomi.com"))
        addAll(e("Xiaomi", ANALYTICS, "data.mistat.xiaomi.com", "tracking.miui.com", "sdkconfig.ad.xiaomi.com", "data.mistat.intl.xiaomi.com"))
        addAll(e("Xiaomi", PUSH, "mipush.xmpush.xiaomi.com", "api.xmpush.xiaomi.com", "xmpush.xiaomi.com"))
        addAll(e("Xiaomi", OS, "miui.com", "xiaomi.net", "micloud.xiaomi.net", "update.miui.com"))
        addAll(e("Xiaomi", UNKNOWN, "xiaomi.com", "mi.com", "mi-img.com", "miwifi.com", "xiaomiev.com", "mijia.tech", "io.mi.com"))
        // Huawei / Honor
        addAll(e("Huawei", PUSH, "push.hicloud.com", "push-api.cloud.huawei.com", "push.dbankcloud.cn"))
        addAll(e("Huawei", ANALYTICS, "metrics.data.hicloud.com", "datacollector.huawei.com", "hianalytics.com"))
        addAll(e("Huawei", OS, "hicloud.com", "dbankcloud.com", "dbankcloud.cn", "hihonorcloud.com", "hihonor.com"))
        addAll(e("Huawei", UNKNOWN, "huawei.com", "vmall.com", "huaweicloud.com", "honor.com"))
        // OPPO / OnePlus / vivo
        addAll(e("OPPO", PUSH, "push.heytapmobi.com", "opush.heytapmobi.com"))
        addAll(e("OPPO", ANALYTICS, "data.ads.oppomobile.com", "dc.heytapmobi.com", "ads.oppomobile.com"))
        addAll(e("OPPO", OS, "heytapmobi.com", "coloros.com", "oppomobile.com", "heytap.com", "oplus.com", "oneplus.com", "oneplus.net"))
        addAll(e("OPPO", UNKNOWN, "oppo.com", "oppo.com.cn"))
        addAll(e("vivo", PUSH, "push.vivo.com.cn", "pushgw.vivo.com.cn"))
        addAll(e("vivo", ANALYTICS, "bbk-dc.vivo.com.cn", "sdk-ad.vivo.com.cn"))
        addAll(e("vivo", OS, "vivo.com.cn", "vivo.com", "vivoglobal.com", "vivoo.com"))
        // ZTE / Nubia
        addAll(e("ZTE", OS, "zte.com.cn", "nubia.com", "ztedevices.com", "nubia.cn", "ztemobile.com"))
        // Carriers
        addAll(e("China Mobile", CARRIER, "10086.cn", "chinamobile.com", "139.com", "cmpassport.com", "cmvideo.cn"))
        addAll(e("China Unicom", CARRIER, "10010.com", "chinaunicom.com", "wo.cn", "wostore.cn", "186.cn"))
        addAll(e("China Telecom", CARRIER, "189.cn", "chinatelecom.cn", "ctyun.cn", "21cn.com", "tyyun.com"))
        // Google
        addAll(e("Google", ADS, "doubleclick.net", "googleadservices.com", "googlesyndication.com", "admob.com", "googletagmanager.com", "googletagservices.com", "adservice.google.com"))
        addAll(e("Google", ANALYTICS, "google-analytics.com", "analytics.google.com", "app-measurement.com", "firebaseinstallations.googleapis.com", "firebaselogging-pa.googleapis.com"))
        addAll(e("Google", CRASH, "crashlytics.com", "firebase-settings.crashlytics.com", "firebasecrashlytics.com"))
        addAll(e("Google", PUSH, "mtalk.google.com", "fcm.googleapis.com", "fcmtoken.googleapis.com", "android.apis.google.com"))
        addAll(e("Google", CDN, "gstatic.com", "googleusercontent.com", "ggpht.com", "gvt1.com", "gvt2.com", "ytimg.com", "googlevideo.com"))
        addAll(e("Google", OS, "connectivitycheck.gstatic.com", "play.googleapis.com", "android.clients.google.com"))
        addAll(e("Google", UNKNOWN, "google.com", "googleapis.com", "youtube.com", "firebaseio.com", "firebase.com", "gmail.com", "withgoogle.com", "android.com"))
        // Meta / Microsoft / Apple / Amazon / others
        addAll(e("Meta", ANALYTICS, "graph.facebook.com", "connect.facebook.net", "facebook.net"))
        addAll(e("Meta", CDN, "fbcdn.net", "cdninstagram.com"))
        addAll(e("Meta", UNKNOWN, "facebook.com", "instagram.com", "whatsapp.com", "whatsapp.net", "fb.com", "meta.com"))
        addAll(e("Microsoft", OS, "msftconnecttest.com", "windowsupdate.com", "windows.com", "windows.net", "msftncsi.com"))
        addAll(e("Microsoft", ANALYTICS, "events.data.microsoft.com", "vortex.data.microsoft.com", "clarity.ms", "appcenter.ms", "in.appcenter.ms"))
        addAll(e("Microsoft", UNKNOWN, "microsoft.com", "live.com", "office.com", "office365.com", "bing.com", "msn.com", "outlook.com", "skype.com", "azure.com", "azureedge.net"))
        addAll(e("Apple", OS, "apple.com", "icloud.com", "mzstatic.com", "apple-dns.net", "cdn-apple.com", "aaplimg.com"))
        addAll(e("Amazon", CDN, "cloudfront.net", "amazonaws.com", "awsstatic.com"))
        addAll(e("Amazon", UNKNOWN, "amazon.com", "amazon.cn", "amazon-adsystem.com"))
        addAll(e("Cloudflare", CDN, "cloudflare.com", "cloudflare.net", "cloudflare-dns.com", "workers.dev", "pages.dev"))
        addAll(e("Akamai", CDN, "akamai.net", "akamaiedge.net", "akamaihd.net", "akamaitechnologies.com", "edgekey.net", "edgesuite.net"))
        addAll(e("Fastly", CDN, "fastly.net", "fastlylb.net", "fastly.com"))
        // Third-party SDK vendors
        addAll(e("AppsFlyer", ANALYTICS, "appsflyer.com", "appsflyersdk.com", "onelink.me"))
        addAll(e("Adjust", ANALYTICS, "adjust.com", "adjust.io", "adjust.net.in", "adj.st"))
        addAll(e("Branch", ANALYTICS, "branch.io", "app.link"))
        addAll(e("Mixpanel", ANALYTICS, "mixpanel.com"))
        addAll(e("Amplitude", ANALYTICS, "amplitude.com"))
        addAll(e("Segment", ANALYTICS, "segment.io", "segment.com"))
        addAll(e("Sentry", CRASH, "sentry.io", "ingest.sentry.io"))
        addAll(e("Datadog", ANALYTICS, "datadoghq.com", "browser-intake-datadoghq.com"))
        addAll(e("Unity", ADS, "unity3d.com", "unityads.unity3d.com", "applovin.com", "ironsrc.com", "vungle.com", "chartboost.com", "mintegral.com", "inmobi.com"))
        addAll(e("JiGuang", PUSH, "jpush.cn", "jiguang.cn", "jpush.io"))
        addAll(e("Getui", PUSH, "getui.com", "getui.net", "igexin.com"))
        addAll(e("Sensors Data", ANALYTICS, "sensorsdata.cn", "sensorsdata.com"))
        addAll(e("GrowingIO", ANALYTICS, "growingio.com"))
    }
}
