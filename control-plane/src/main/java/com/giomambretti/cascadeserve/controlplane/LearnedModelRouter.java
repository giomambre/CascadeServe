package com.giomambretti.cascadeserve.controlplane;

import com.giomambretti.cascadeserve.v1.GenerateRequest;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LearnedModelRouter implements ModelRouter {
    private final double intercept;
    private final double threshold;
    private final double wordWeight;
    private final double charWeight;
    private final double digitWeight;
    private final double symbolWeight;
    private final double newlineWeight;

    public LearnedModelRouter(Path modelPath) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(modelPath)) {
            properties.load(reader);
        } catch (IOException error) {
            throw new IllegalArgumentException("could not load router model: " + modelPath, error);
        }
        intercept = requiredDouble(properties, "intercept");
        threshold = requiredDouble(properties, "threshold");
        wordWeight = requiredDouble(properties, "weight.word_count");
        charWeight = requiredDouble(properties, "weight.char_count");
        digitWeight = requiredDouble(properties, "weight.digit_count");
        symbolWeight = requiredDouble(properties, "weight.symbol_count");
        newlineWeight = requiredDouble(properties, "weight.newline_count");
        if (threshold <= 0 || threshold >= 1) {
            throw new IllegalArgumentException("router threshold must be between zero and one");
        }
    }

    @Override
    public String policy() {
        return "learned";
    }

    @Override
    public RoutingDecision route(GenerateRequest request) {
        PromptFeatures features = PromptFeatures.from(request.getPrompt());
        double logit = intercept
                + wordWeight * features.wordCount()
                + charWeight * features.charCount()
                + digitWeight * features.digitCount()
                + symbolWeight * features.symbolCount()
                + newlineWeight * features.newlineCount();
        double probability = sigmoid(logit);
        return new RoutingDecision(probability >= threshold ? "large" : "small", probability);
    }

    private double sigmoid(double value) {
        if (value >= 0) {
            return 1.0 / (1.0 + Math.exp(-value));
        }
        double exponential = Math.exp(value);
        return exponential / (1.0 + exponential);
    }

    private double requiredDouble(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("router model is missing property: " + key);
        }
        return Double.parseDouble(value);
    }
}
