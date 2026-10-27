package me.sorihiko.dango.music

object KugouLiteApi : BaseKugouApi() {
    override val appid = 3116
    override val clientver = 11440
    override val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    override val signKeySalt = "185672dd44712f60bb1736df5a377e82"
    override val publicKeyBase64 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
    override val trackPlatform = "KuGou Lite"
    override val login get() = KugouLiteLogin
    override val pageId = "967177915"
    override val pid = "411"
    override val parentPageId = "356753938,823673182,967485191"
}
