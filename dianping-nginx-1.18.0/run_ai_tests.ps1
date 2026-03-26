$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'

$baseUrl = "http://127.0.0.1:8080/api"
$defaultX = 120.149993
$defaultY = 30.334229
$defaultTypeId = 1

function Decode-Unicode([string]$s) {
    return ("`"$s`"" | ConvertFrom-Json)
}

function Decode-UnicodeList($arr) {
    if ($null -eq $arr) { return @() }
    return @($arr | ForEach-Object { Decode-Unicode ([string]$_) })
}

function Invoke-ApiPost([string]$path, [hashtable]$body, [int]$timeoutSec = 40) {
    try {
        $jsonBody = $body | ConvertTo-Json -Depth 8
        $resp = Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($baseUrl + $path) -ContentType "application/json; charset=utf-8" -Body $jsonBody -TimeoutSec $timeoutSec
        $bytes = $resp.RawContentStream.ToArray()
        $content = [System.Text.Encoding]::UTF8.GetString($bytes)
        $obj = $content | ConvertFrom-Json
        return @{
            ok = $true
            raw = $obj
            data = $obj.data
            message = ""
        }
    } catch {
        return @{
            ok = $false
            raw = $null
            data = $null
            message = $_.Exception.Message
        }
    }
}

function Invoke-ApiGet([string]$path, [int]$timeoutSec = 40) {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -Method Get -Uri ($baseUrl + $path) -TimeoutSec $timeoutSec
        $bytes = $resp.RawContentStream.ToArray()
        $content = [System.Text.Encoding]::UTF8.GetString($bytes)
        $obj = $content | ConvertFrom-Json
        return @{
            ok = $true
            raw = $obj
            data = $obj.data
            message = ""
        }
    } catch {
        return @{
            ok = $false
            raw = $null
            data = $null
            message = $_.Exception.Message
        }
    }
}

function Join-ShopText($shop, [bool]$includeReason = $false) {
    $name = if ($null -eq $shop.name) { "" } else { [string]$shop.name }
    $desc = if ($null -eq $shop.shopDesc) { "" } else { [string]$shop.shopDesc }
    $text = $name + " " + $desc
    if ($includeReason) {
        $reason = if ($null -eq $shop.reason) { "" } else { [string]$shop.reason }
        $text = $text + " " + $reason
    }
    return $text.ToLowerInvariant()
}

function Count-KeywordHits($shops, $keywords) {
    if ($null -eq $shops -or $null -eq $keywords -or $keywords.Count -eq 0) {
        return 0
    }
    $count = 0
    foreach ($shop in $shops) {
        $blob = Join-ShopText $shop $false
        foreach ($kw in $keywords) {
            if ([string]::IsNullOrWhiteSpace($kw)) { continue }
            if ($blob.Contains(([string]$kw).ToLowerInvariant())) {
                $count++
                break
            }
        }
    }
    return $count
}

function Count-TopNExcludeHits($shops, $excludeKeywords, [int]$topN = 3) {
    if ($null -eq $shops -or $shops.Count -eq 0 -or $null -eq $excludeKeywords -or $excludeKeywords.Count -eq 0) {
        return 0
    }
    $top = @($shops | Select-Object -First $topN)
    $count = 0
    foreach ($shop in $top) {
        $blob = Join-ShopText $shop $false
        foreach ($kw in $excludeKeywords) {
            if ([string]::IsNullOrWhiteSpace($kw)) { continue }
            if ($blob.Contains(([string]$kw).ToLowerInvariant())) {
                $count++
                break
            }
        }
    }
    return $count
}

function Normalize-Price($value) {
    if ($null -eq $value) { return $null }
    try {
        return [double]$value
    } catch {
        return $null
    }
}

function Normalize-DistanceMeter($value) {
    if ($null -eq $value) { return $null }
    try {
        $d = [double]$value
        if ($d -le 10) {
            return $d * 1000
        }
        return $d
    } catch {
        return $null
    }
}

function Summarize-AssistantCase($case, $resp1, $resp2) {
    $result = [ordered]@{
        case = $case.id
        query = $case.query
        ok = $resp1.ok
        error = $resp1.message
        cacheOnSecond = $false
        recommendCount = 0
        includeHitCount = 0
        top3ExcludeHitCount = 0
        top3WithinBudgetCount = 0
        top3WithinDistanceCount = 0
        reasonEchoCount = 0
        top = @()
    }
    if (-not $resp1.ok -or $null -eq $resp1.data) {
        return $result
    }

    $shops = @($resp1.data.recommendShops)
    $result.recommendCount = $shops.Count
    $result.cacheOnSecond = [bool]($resp2.ok -and $resp2.data -and $resp2.data.fromCache)
    $result.includeHitCount = Count-KeywordHits $shops $case.includeKeywords
    $result.top3ExcludeHitCount = Count-TopNExcludeHits $shops $case.excludeKeywords 3
    $queryLower = [string]$case.query
    $queryLower = $queryLower.ToLowerInvariant()
    $echo = 0
    foreach ($shop in @($shops | Select-Object -First 5)) {
        $reason = if ($null -eq $shop.reason) { "" } else { ([string]$shop.reason).ToLowerInvariant() }
        if ($queryLower -ne "" -and $reason.Contains($queryLower)) {
            $echo++
        }
    }
    $result.reasonEchoCount = $echo

    if ($case.budgetMax -gt 0) {
        $within = 0
        foreach ($shop in @($shops | Select-Object -First 3)) {
            $price = Normalize-Price $shop.avgPrice
            if ($null -ne $price -and $price -le $case.budgetMax) {
                $within++
            }
        }
        $result.top3WithinBudgetCount = $within
    }

    if ($case.maxDistanceMeter -gt 0) {
        $withinDist = 0
        foreach ($shop in @($shops | Select-Object -First 3)) {
            $dist = Normalize-DistanceMeter $shop.distance
            if ($null -ne $dist -and $dist -le $case.maxDistanceMeter) {
                $withinDist++
            }
        }
        $result.top3WithinDistanceCount = $withinDist
    }

    $result.top = @(
        $shops | Select-Object -First 5 | ForEach-Object {
            [ordered]@{
                id = $_.id
                name = $_.name
                typeId = $_.typeId
                avgPrice = $_.avgPrice
                score = $_.score
                distance = $_.distance
                reason = $_.reason
            }
        }
    )
    return $result
}

function Evaluate-AssistantPass($caseResult, $caseSpec) {
    if (-not $caseResult.ok) { return $false }
    if ($caseResult.recommendCount -lt 3) { return $false }
    if ($caseSpec.minIncludeHits -gt 0 -and $caseResult.includeHitCount -lt $caseSpec.minIncludeHits) { return $false }
    if ($caseSpec.maxTop3ExcludeHits -ge 0 -and $caseResult.top3ExcludeHitCount -gt $caseSpec.maxTop3ExcludeHits) { return $false }
    if ($caseSpec.requireBudgetTop3 -gt 0 -and $caseResult.top3WithinBudgetCount -lt $caseSpec.requireBudgetTop3) { return $false }
    if ($caseSpec.requireDistanceTop3 -gt 0 -and $caseResult.top3WithinDistanceCount -lt $caseSpec.requireDistanceTop3) { return $false }
    return $true
}

function Summarize-RiskCase($case, $resp1, $resp2) {
    $result = [ordered]@{
        case = $case.id
        ok = $resp1.ok
        error = $resp1.message
        pass = $null
        riskLevel = ""
        riskScore = $null
        riskTags = @()
        reasons = @()
        suggestion = ""
        cacheOnSecond = $false
        expected = $case.expected
        expectationMet = $false
    }
    if (-not $resp1.ok -or $null -eq $resp1.data) {
        return $result
    }

    $data = $resp1.data
    $result.pass = $data.pass
    $result.riskLevel = [string]$data.riskLevel
    $result.riskScore = $data.riskScore
    $result.riskTags = @($data.riskTags)
    $result.reasons = @($data.reasons)
    $result.suggestion = [string]$data.suggestion
    $result.cacheOnSecond = [bool]($resp2.ok -and $resp2.data -and $resp2.data.fromCache)

    if ($case.expected -eq "SAFE_PASS") {
        $result.expectationMet = ([bool]$data.pass -eq $true) -and (([string]$data.riskLevel) -eq "SAFE")
    } else {
        $score = 0
        if ($null -ne $data.riskScore) { $score = [int]$data.riskScore }
        $isRiskLevel = (([string]$data.riskLevel) -ne "") -and (([string]$data.riskLevel) -ne "SAFE")
        $result.expectationMet = ([bool]$data.pass -eq $false) -or $isRiskLevel -or ($score -ge 60)
    }
    return $result
}

function Get-NearbyShopIds() {
    $resp = Invoke-ApiGet "/shop/of/type?typeId=1&current=1&x=$defaultX&y=$defaultY" 30
    if (-not $resp.ok -or $null -eq $resp.data) {
        return @()
    }
    $shops = @($resp.data)
    return @(
        $shops |
            Where-Object { $null -ne $_.id } |
            Select-Object -First 8 -ExpandProperty id
    )
}

function Summarize-ShopSummary($shopId) {
    $resp1 = Invoke-ApiGet "/ai/shop/$shopId/summary" 50
    $resp2 = Invoke-ApiGet "/ai/shop/$shopId/summary" 50
    $result = [ordered]@{
        shopId = $shopId
        ok = $resp1.ok
        error = $resp1.message
        fromCacheSecond = $false
        reviewCount = 0
        chunkCount = 0
        highFreqCount = 0
        uniqueCount = 0
        noiseHitCount = 0
        noisyPoints = @()
    }
    if (-not $resp1.ok -or $null -eq $resp1.data) {
        return $result
    }
    $d = $resp1.data
    $result.reviewCount = $d.reviewCount
    $result.chunkCount = $d.chunkCount
    $result.fromCacheSecond = [bool]($resp2.ok -and $resp2.data -and $resp2.data.fromCache)
    $high = @($d.highFrequencyHighlights)
    $uniq = @($d.uniqueHighlights)
    $result.highFreqCount = $high.Count
    $result.uniqueCount = $uniq.Count
    $allPoints = @($high + $uniq)
    $noisePattern = "(AI\s*\u6837\u672c\u5e97|AI\s*\u63a2\u5e97|\u672c\u6b21\u91cd\u70b9\u5173\u6ce8|-\s*\d{1,4}\s*-\s*\d{1,4})"
    $noisy = @()
    foreach ($p in $allPoints) {
        if ($null -eq $p) { continue }
        if ([regex]::IsMatch([string]$p, $noisePattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            $noisy += [string]$p
        }
    }
    $result.noiseHitCount = $noisy.Count
    $result.noisyPoints = $noisy | Select-Object -First 5
    return $result
}

$assistantCases = @(
    @{
        id = "regular_bbq_cheap"
        query = Decode-Unicode "\u6211\u60f3\u5403\u70e7\u70e4\uff0c\u6709\u6ca1\u6709\u4ef7\u683c\u4fbf\u5b9c\u70b9\u7684"
        includeKeywords = Decode-UnicodeList @("\u70e7\u70e4", "\u70e4", "\u4e32", "\u70e4\u8089")
        excludeKeywords = Decode-UnicodeList @("\u65e5\u6599", "\u751c\u54c1")
        minIncludeHits = 2
        maxTop3ExcludeHits = 2
        budgetMax = 80
        requireBudgetTop3 = 1
        maxDistanceMeter = 0
        requireDistanceTop3 = 0
    },
    @{
        id = "regular_light_veggie"
        query = Decode-Unicode "\u6211\u6700\u8fd1\u80a0\u80c3\u4e0d\u597d\u60f3\u5403\u7d20\uff0c\u6e05\u6de1\u4e00\u70b9"
        includeKeywords = Decode-UnicodeList @("\u7d20", "\u6e05\u6de1", "\u7ca5", "\u9762", "\u852c")
        excludeKeywords = Decode-UnicodeList @("\u706b\u9505", "\u6dae", "\u7f8a\u8089", "\u725b\u8089", "\u70e7\u70e4", "\u70e4\u8089")
        minIncludeHits = 1
        maxTop3ExcludeHits = 1
        budgetMax = 0
        requireBudgetTop3 = 0
        maxDistanceMeter = 0
        requireDistanceTop3 = 0
    },
    @{
        id = "regular_water_tea_rest"
        query = Decode-Unicode "\u60f3\u4e70\u74f6\u6c34\uff0c\u9644\u8fd1\u6709\u53ef\u4ee5\u4f11\u606f\u559d\u8336\u7684\u5e97\u5417"
        includeKeywords = Decode-UnicodeList @("\u6c34", "\u8336", "\u4f11\u606f", "\u996e", "\u5496\u5561", "\u5976\u8336")
        excludeKeywords = Decode-UnicodeList @("\u706b\u9505", "\u6dae\u7f8a\u8089")
        minIncludeHits = 1
        maxTop3ExcludeHits = 1
        budgetMax = 0
        requireBudgetTop3 = 0
        maxDistanceMeter = 0
        requireDistanceTop3 = 0
    },
    @{
        id = "complex_conflict_constraints"
        query = Decode-Unicode "\u9884\u7b9750\u4ee5\u5185\uff0c1km\u5185\uff0c\u60f3\u8981\u70e7\u70e4\u98ce\u5473\u4f46\u4e0d\u8981\u8fa3\u4e5f\u4e0d\u8981\u8089\uff0c\u8981\u80fd\u5750\u7740\u4f11\u606f"
        includeKeywords = Decode-UnicodeList @("\u6e05\u6de1", "\u7d20", "\u8336", "\u4f11\u606f", "\u70e7\u70e4")
        excludeKeywords = Decode-UnicodeList @("\u706b\u9505", "\u7f8a\u8089", "\u725b\u8089", "\u91cd\u8fa3", "\u9ebb\u8fa3")
        minIncludeHits = 2
        maxTop3ExcludeHits = 1
        budgetMax = 50
        requireBudgetTop3 = 1
        maxDistanceMeter = 1000
        requireDistanceTop3 = 1
    },
    @{
        id = "complex_multi_intent_health"
        query = Decode-Unicode "\u4eca\u5929\u611f\u5192\u6ca1\u80c3\u53e3\uff0c\u60f3\u5148\u4e70\u6c34\u518d\u627e\u80fd\u77ed\u4f11\u7684\u5e97\uff0c\u4eba\u5747100\u4ee5\u5185\uff0c\u8bc4\u5206\u9ad8\u4e00\u70b9"
        includeKeywords = Decode-UnicodeList @("\u6c34", "\u8336", "\u4f11\u606f", "\u6e05\u6de1", "\u7ca5")
        excludeKeywords = Decode-UnicodeList @("\u91cd\u8fa3", "\u9ebb\u8fa3", "\u7f8a\u8089", "\u70e4\u8089")
        minIncludeHits = 2
        maxTop3ExcludeHits = 1
        budgetMax = 100
        requireBudgetTop3 = 1
        maxDistanceMeter = 0
        requireDistanceTop3 = 0
    },
    @{
        id = "complex_noise_colloquial"
        query = Decode-Unicode "\u54e5\u4eec\u4eca\u5929\u7d2f\u9ebb\u4e86\uff0c\u60f3\u627e\u4e2a\u80fd\u8eba\u4e00\u4e0b\u559d\u70ed\u6c34\u7684\u5730\u65b9\uff0c\u522b\u592a\u8d35\uff0c\u522b\u6574\u592a\u6cb9\u7684"
        includeKeywords = Decode-UnicodeList @("\u70ed\u6c34", "\u4f11\u606f", "\u8336", "\u6e05\u6de1")
        excludeKeywords = Decode-UnicodeList @("\u706b\u9505", "\u91cd\u8fa3", "\u70e4\u8089")
        minIncludeHits = 1
        maxTop3ExcludeHits = 1
        budgetMax = 80
        requireBudgetTop3 = 1
        maxDistanceMeter = 0
        requireDistanceTop3 = 0
    }
)

$riskCases = @(
    @{
        id = "safe_normal"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u666e\u901a\u63a2\u5e97\u7b14\u8bb0"
            content = Decode-Unicode "\u73af\u5883\u5e72\u51c0\uff0c\u51fa\u54c1\u7a33\u5b9a\uff0c\u4ef7\u683c\u4e5f\u5408\u7406\uff0c\u603b\u4f53\u4f53\u9a8c\u4e0d\u9519\u3002"
            shopId = 1
        }
        expected = "SAFE_PASS"
    },
    @{
        id = "ad_contact_direct"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u6d3b\u52a8\u798f\u5229"
            content = Decode-Unicode "\u5230\u5e97\u8bf4\u6697\u53f7\u6253\u4e03\u6298\uff0c\u52a0\u5fae\u4fe1abc123456\u9886\u7ea2\u5305\uff0c\u652f\u6301\u5f02\u5730\u4e0b\u5355\u3002"
            shopId = 1
        }
        expected = "RISK_BLOCK"
    },
    @{
        id = "illegal_content"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u9ad8\u98ce\u9669\u5185\u5bb9"
            content = Decode-Unicode "\u6709\u4eba\u5728\u8fd9\u4e70\u8fdd\u7981\u836f\uff0c\u6559\u4f60\u600e\u4e48\u9003\u9047\u76d1\u7ba1\uff0c\u5feb\u8054\u7cfb\u6211\u3002"
            shopId = 1
        }
        expected = "RISK_BLOCK"
    },
    @{
        id = "privacy_leak"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u7206\u6599"
            content = Decode-Unicode "\u5e97\u5458\u5f20\u4e09\uff0c\u624b\u673a13800138000\uff0c\u4f4f\u5728\u676d\u5ddeXX\u8defXX\u53f7\uff0c\u5927\u5bb6\u53ef\u4ee5\u76f4\u63a5\u627e\u4ed6\u3002"
            shopId = 1
        }
        expected = "RISK_BLOCK"
    },
    @{
        id = "abuse_hate"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u5dee\u8bc4"
            content = Decode-Unicode "\u8001\u677f\u662f\u50bbX\uff0c\u5e97\u91cc\u4eba\u90fd\u662f\u5783\u573e\uff0c\u5efa\u8bae\u53bb\u780d\u5e97\u3002"
            shopId = 1
        }
        expected = "RISK_BLOCK"
    },
    @{
        id = "obfuscated_ad"
        payload = @{
            scene = "BLOG_NOTE"
            title = Decode-Unicode "\u798f\u5229\u4e0d\u591a\u8bf4"
            content = Decode-Unicode "\u8bc4\u8bba\u56de\u590d\u2018\u60f3\u77e5\u9053\u2019\u53d1\u9690\u85cf\u8054\u7cfb\u65b9\u5f0f\uff0c\u5916\u5730\u4e5f\u80fd\u5bc4\uff0c\u4f18\u60e0\u6700\u5927\u3002"
            shopId = 1
        }
        expected = "RISK_BLOCK"
    }
)

$assistantResults = @()
foreach ($case in $assistantCases) {
    $payload = @{
        query = $case.query
        x = $defaultX
        y = $defaultY
        currentTypeId = $defaultTypeId
    }
    $resp1 = Invoke-ApiPost "/ai/assistant/recommend" $payload 45
    $resp2 = Invoke-ApiPost "/ai/assistant/recommend" $payload 45
    $caseResult = Summarize-AssistantCase $case $resp1 $resp2
    $caseResult["pass"] = Evaluate-AssistantPass $caseResult $case
    $assistantResults += [pscustomobject]$caseResult
}

$riskResults = @()
foreach ($case in $riskCases) {
    $resp1 = Invoke-ApiPost "/ai/review/risk-check" $case.payload 45
    $resp2 = Invoke-ApiPost "/ai/review/risk-check" $case.payload 45
    $riskResults += [pscustomobject](Summarize-RiskCase $case $resp1 $resp2)
}

$nearbyShopIds = Get-NearbyShopIds
$summaryResults = @()
foreach ($shopId in $nearbyShopIds) {
    $summaryResults += [pscustomobject](Summarize-ShopSummary $shopId)
}

$top1List = @(
    $assistantResults | ForEach-Object {
        if ($_.top -and $_.top.Count -gt 0) { $_.top[0].name } else { "" }
    } | Where-Object { $_ -ne "" }
)

$top3NameSets = @{}
foreach ($r in $assistantResults) {
    $names = @()
    if ($r.top) {
        $names = @($r.top | Select-Object -First 3 | ForEach-Object { $_.name } | Where-Object { $_ })
    }
    $top3NameSets[$r.case] = $names
}

$pairs = @()
$caseNames = @($top3NameSets.Keys)
for ($i = 0; $i -lt $caseNames.Count; $i++) {
    for ($j = $i + 1; $j -lt $caseNames.Count; $j++) {
        $a = $top3NameSets[$caseNames[$i]]
        $b = $top3NameSets[$caseNames[$j]]
        $setA = @($a | Select-Object -Unique)
        $setB = @($b | Select-Object -Unique)
        $inter = @($setA | Where-Object { $setB -contains $_ })
        $union = @($setA + $setB | Select-Object -Unique)
        $jaccard = if ($union.Count -eq 0) { 0 } else { [Math]::Round(($inter.Count / $union.Count), 3) }
        $pairs += [pscustomobject]@{
            caseA = $caseNames[$i]
            caseB = $caseNames[$j]
            jaccardTop3 = $jaccard
            overlapNames = $inter
        }
    }
}

$report = [ordered]@{
    timestamp = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    baseUrl = $baseUrl
    assistant = [ordered]@{
        passCount = (@($assistantResults | Where-Object { $_.pass }).Count)
        totalCount = $assistantResults.Count
        cacheHitSecondCount = (@($assistantResults | Where-Object { $_.cacheOnSecond }).Count)
        uniqueTop1Count = (@($top1List | Select-Object -Unique).Count)
        cases = $assistantResults
        overlapPairs = $pairs
    }
    risk = [ordered]@{
        passCount = (@($riskResults | Where-Object { $_.expectationMet }).Count)
        totalCount = $riskResults.Count
        cacheHitSecondCount = (@($riskResults | Where-Object { $_.cacheOnSecond }).Count)
        cases = $riskResults
    }
    summary = [ordered]@{
        testedShopCount = $summaryResults.Count
        noisyShopCount = (@($summaryResults | Where-Object { $_.noiseHitCount -gt 0 }).Count)
        cacheHitSecondCount = (@($summaryResults | Where-Object { $_.fromCacheSecond }).Count)
        shops = $summaryResults
    }
}

$jsonPath = Join-Path (Get-Location) "ai_test_results_v2.json"
$report | ConvertTo-Json -Depth 10 | Out-File -FilePath $jsonPath -Encoding utf8

$lines = @()
$lines += "# AI Test Report V2"
$lines += ""
$lines += "## 1) Assistant Recommend"
$lines += ("- Pass: {0}/{1}" -f $report.assistant.passCount, $report.assistant.totalCount)
$lines += ("- Cache hit on 2nd call: {0}/{1}" -f $report.assistant.cacheHitSecondCount, $report.assistant.totalCount)
$lines += ("- Unique Top1 across queries: {0}" -f $report.assistant.uniqueTop1Count)
$lines += ""
foreach ($c in $assistantResults) {
    $lines += ("- [{0}] pass={1}, rec={2}, includeHits={3}, top3ExcludeHits={4}, budgetTop3={5}, distTop3={6}, reasonEcho={7}" -f $c.case, $c.pass, $c.recommendCount, $c.includeHitCount, $c.top3ExcludeHitCount, $c.top3WithinBudgetCount, $c.top3WithinDistanceCount, $c.reasonEchoCount)
}
$lines += ""
$lines += "## 2) Risk Check"
$lines += ("- Expectation met: {0}/{1}" -f $report.risk.passCount, $report.risk.totalCount)
$lines += ("- Cache hit on 2nd call: {0}/{1}" -f $report.risk.cacheHitSecondCount, $report.risk.totalCount)
foreach ($r in $riskResults) {
    $lines += ("- [{0}] expected={1}, met={2}, pass={3}, level={4}, score={5}" -f $r.case, $r.expected, $r.expectationMet, $r.pass, $r.riskLevel, $r.riskScore)
}
$lines += ""
$lines += "## 3) Shop Summary Noise"
$lines += ("- Tested shops: {0}" -f $report.summary.testedShopCount)
$lines += ("- Noisy shops: {0}" -f $report.summary.noisyShopCount)
$lines += ("- Cache hit on 2nd call: {0}/{1}" -f $report.summary.cacheHitSecondCount, $report.summary.testedShopCount)
foreach ($s in $summaryResults) {
    $lines += ("- [shop:{0}] noiseHit={1}, high={2}, unique={3}" -f $s.shopId, $s.noiseHitCount, $s.highFreqCount, $s.uniqueCount)
}

$mdPath = Join-Path (Get-Location) "ai_test_report_v2.md"
$lines -join "`r`n" | Out-File -FilePath $mdPath -Encoding utf8

Write-Output ("DONE json={0}" -f $jsonPath)
Write-Output ("DONE md={0}" -f $mdPath)
