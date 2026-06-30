var STORAGE_KEY = 'billy-gemini-usage-v1';

var FLASH_LITE_INPUT_PER_MILLION = 0.25;
var FLASH_LITE_OUTPUT_PER_MILLION = 1.50;
var SEARCH_FREE_PER_MONTH = 5000;
var SEARCH_PER_THOUSAND = 14.00;

function emptyUsage() {
    return {
        period: currentPeriod(),
        inputTokens: 0,
        outputTokens: 0,
        totalTokens: 0,
        groundedSearches: 0,
        requestCount: 0
    };
}

function currentPeriod() {
    var now = new Date();
    return now.getFullYear() + '-' + ('0' + (now.getMonth() + 1)).slice(-2);
}

function load() {
    try {
        var raw = localStorage.getItem(STORAGE_KEY);
        var usage = raw ? JSON.parse(raw) : emptyUsage();
        if (usage.period !== currentPeriod()) {
            return emptyUsage();
        }
        return usage;
    } catch (e) {
        return emptyUsage();
    }
}

function save(usage) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(usage));
}

function tokenCount(metadata, names) {
    if (!metadata) {
        return 0;
    }
    for (var i = 0; i < names.length; i++) {
        var value = metadata[names[i]];
        if (typeof value === 'number') {
            return value;
        }
    }
    return 0;
}

exports.recordGeminiResponse = function(response) {
    if (!response || !response.raw) {
        return;
    }
    var metadata = response.raw.usageMetadata || response.raw.usage || {};
    var inputTokens = tokenCount(metadata, ['promptTokenCount', 'input_tokens', 'inputTokens']);
    var outputTokens = tokenCount(metadata, ['candidatesTokenCount', 'output_tokens', 'outputTokens']);
    var totalTokens = tokenCount(metadata, ['totalTokenCount', 'total_tokens', 'totalTokens']);
    if (!inputTokens && !outputTokens && !totalTokens) {
        return;
    }
    var usage = load();
    usage.inputTokens += inputTokens;
    usage.outputTokens += outputTokens;
    usage.totalTokens += totalTokens || (inputTokens + outputTokens);
    usage.requestCount += 1;
    save(usage);
}

exports.recordGroundedSearch = function() {
    var usage = load();
    usage.groundedSearches += 1;
    save(usage);
}

exports.getSummary = function(budgetUsd) {
    var usage = load();
    var inputCost = usage.inputTokens / 1000000 * FLASH_LITE_INPUT_PER_MILLION;
    var outputCost = usage.outputTokens / 1000000 * FLASH_LITE_OUTPUT_PER_MILLION;
    var billableSearches = Math.max(0, usage.groundedSearches - SEARCH_FREE_PER_MONTH);
    var searchCost = billableSearches / 1000 * SEARCH_PER_THOUSAND;
    var estimatedCost = inputCost + outputCost + searchCost;
    var budget = isFinite(budgetUsd) && budgetUsd > 0 ? budgetUsd : 10;
    return {
        period: usage.period,
        budgetUsd: budget,
        estimatedCostUsd: estimatedCost,
        inputTokens: usage.inputTokens,
        outputTokens: usage.outputTokens,
        totalTokens: usage.totalTokens,
        requestCount: usage.requestCount,
        groundedSearches: usage.groundedSearches,
        remainingUsd: Math.max(0, budget - estimatedCost),
        percentUsed: Math.max(0, Math.min(100, Math.round((estimatedCost / budget) * 100)))
    };
}
