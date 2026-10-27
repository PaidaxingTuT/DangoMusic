package me.sorihiko.dango.music

object KugouApi : BaseKugouApi() {
    override val appid = 1005
    override val clientver = 20489
    override val salt = "OIlwieks28dk2k092lksi2UIkp"
    override val signKeySalt = "57ae12eb6890223e355ccfcb74edf70d"
    override val publicKeyBase64 = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"
    override val trackPlatform = "KuGou"
    override val login get() = KugouLogin
    override val pageId = "151369488"
    override val pid = "2"
    override val parentPageId = "463467626,350369493,788954147"
}
