var STORAGE_KEY = 'billy-gemini-usage-v1';

// USD per million tokens (paid tier, text). Unknown models use the 3.8 Flash rate.
var PRICES = {
    'gemini-3.8-flash': [0.75, 3.75],
    'gemini-3.7-flash': [0.75, 3.75],
    'gemini-3.5-flash': [1.50, 9.00],
    'gemini-3.5-flash-lite': [0.30, 2.50],
    'gemini-3.1-flash-lite': [0.25, 1.50],
    'gemini-3.1-pro-preview': [2.00, 12.00]
};
var DEFAULT_PRICE = PRICES['gemini-3.8-flash'];
var SEARCH_FREE_PER_MONTH = 5000;
var SEARCH_PER_THOUSAND = 14.00;

function emptyUsage() {
    return {
        period: currentPeriod(),
        inputTokens: 0,
        outputTokens: 0,
        costUsd: 0,
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
    var outputTokens = tokenCount(metadata, ['candidatesTokenCount', 'output_tokens', 'outputTokens']) +
        tokenCount(metadata, ['thoughtsTokenCount']);
    var totalTokens = tokenCount(metadata, ['totalTokenCount', 'total_tokens', 'totalTokens']);
    if (!inputTokens && !outputTokens && !totalTokens) {
        return;
    }
    var usage = load();
    usage.inputTokens += inputTokens;
    usage.outputTokens += outputTokens;
    usage.totalTokens += totalTokens || (inputTokens + outputTokens);
    usage.requestCount += 1;
    var price = PRICES[response.model] || DEFAULT_PRICE;
    usage.costUsd = (usage.costUsd || 0) + inputTokens / 1000000 * price[0] + outputTokens / 1000000 * price[1];
    save(usage);
}

exports.recordGroundedSearch = function() {
    var usage = load();
    usage.groundedSearches += 1;
    save(usage);
}

exports.getSummary = function(budgetUsd) {
    var usage = load();
    var tokenCost = usage.costUsd !== undefined ? usage.costUsd :
        usage.inputTokens / 1000000 * DEFAULT_PRICE[0] + usage.outputTokens / 1000000 * DEFAULT_PRICE[1];
    var billableSearches = Math.max(0, usage.groundedSearches - SEARCH_FREE_PER_MONTH);
    var searchCost = billableSearches / 1000 * SEARCH_PER_THOUSAND;
    var estimatedCost = tokenCost + searchCost;
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
