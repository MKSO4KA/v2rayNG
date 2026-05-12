package com.v2ray.ang.dto

data class NearbyPackageDto(
    val profiles: List<ProfileItem>?,
    val subscriptions: List<SubscriptionCache>?,
    val rulesets: List<RulesetItem>?,
    val settings: Map<String, String>?,
    val mimicryPresets: List<MimicryPreset>?
)

