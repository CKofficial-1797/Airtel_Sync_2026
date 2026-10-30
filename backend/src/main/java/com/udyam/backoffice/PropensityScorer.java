package com.udyam.backoffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Scores a lead with the logistic regression trained by ai-service/ml/train_propensity.py. No Python at runtime: the
 * model is a JSON file of coefficients, and PropensityScorerTest holds this class to Python's numbers.
 *
 * A missing or unknown value falls back to the feature's reference level, which contributes nothing to the score.
 */
@Component
public class PropensityScorer {

    public static final String HOT = "HOT";
    public static final String WARM = "WARM";
    public static final String COLD = "COLD";

    private static final int TOP_REASONS = 3;

    private record Feature(String name, String inputField, String reference, List<Integer> edges, List<String> levels) {
    }

    private final JsonNode model;
    private final List<Feature> features = new ArrayList<>();
    private final Map<String, Double> coefficients;
    private final double intercept;
    private final double hot;
    private final double warm;
    private final double[] percentiles;
    private final Set<String> noNegativeReason = new HashSet<>();

    public PropensityScorer(ObjectMapper json) {
        try (InputStream in = new ClassPathResource("ml/propensity-model-v1.json").getInputStream()) {
            model = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the propensity model", e);
        }
        for (JsonNode f : model.get("features")) {
            List<Integer> edges = new ArrayList<>();
            f.path("edges").forEach(e -> edges.add(e.asInt()));
            List<String> levels = new ArrayList<>();
            f.get("levels").forEach(l -> levels.add(l.asText()));
            features.add(new Feature(f.get("name").asText(), f.path("inputField").asText(f.get("name").asText()),
                    f.get("reference").asText(), edges, levels));
        }
        coefficients = new java.util.HashMap<>();
        model.get("coefficients").properties().forEach(e -> coefficients.put(e.getKey(), e.getValue().asDouble()));
        intercept = model.get("intercept").asDouble();
        hot = model.get("bands").get(HOT).asDouble();
        warm = model.get("bands").get(WARM).asDouble();
        JsonNode p = model.get("scorePercentiles");
        percentiles = new double[p.size()];
        for (int i = 0; i < p.size(); i++) {
            percentiles[i] = p.get(i).asDouble();
        }
        model.get("noNegativeReason").forEach(n -> noNegativeReason.add(n.asText()));
    }

    /** direction: "+" raises the score, "-" lowers it; contribution is the coefficient of the level this lead has. */
    public record Reason(String labelEn, String labelHi, String direction, double contribution) {
    }

    /** score0to100 is a percentile among the historical leads, so 80+ is HOT; probability is the model's own estimate. */
    public record Score(double probability, int score0to100, String band, List<Reason> topReasons) {
    }

    /** input: feature name (or a bucket's inputField) to raw value; see LeadProfile.modelInput. */
    public Score score(Map<String, Object> input) {
        double z = intercept;
        List<Reason> reasons = new ArrayList<>();
        for (Feature f : features) {
            String level = levelOf(f, input.get(f.inputField()));
            String key = f.name() + "=" + level;
            Double coefficient = coefficients.get(key);
            if (coefficient == null) {
                continue; // the reference level has no coefficient
            }
            z += coefficient;
            if (coefficient > 0 || !noNegativeReason.contains(f.name())) {
                JsonNode label = model.get("humanLabels").get(key);
                reasons.add(new Reason(label.get("en").asText(), label.get("hi").asText(), coefficient >= 0 ? "+" : "-",
                        Math.round(coefficient * 1000) / 1000.0));
            }
        }
        reasons.sort(Comparator.comparingDouble((Reason r) -> Math.abs(r.contribution())).reversed());
        double probability = 1 / (1 + Math.exp(-z));
        return new Score(probability, percentile(probability), bandOf(probability),
                List.copyOf(reasons.subList(0, Math.min(TOP_REASONS, reasons.size()))));
    }

    public String bandOf(double probability) {
        if (probability >= hot) {
            return HOT;
        }
        return probability >= warm ? WARM : COLD;
    }

    /** For the insights page: variant, metrics and the lift table, exactly as training reported them. */
    public JsonNode modelInfo() {
        return model;
    }

    private int percentile(double probability) {
        int i = java.util.Arrays.binarySearch(percentiles, probability);
        int position = i >= 0 ? i : -i - 2;
        return Math.max(0, Math.min(100, position));
    }

    private static String levelOf(Feature f, Object value) {
        if (value == null) {
            return f.reference();
        }
        if (!f.edges().isEmpty()) {
            if (!(value instanceof Number number)) {
                return f.reference();
            }
            for (int i = 0; i < f.edges().size(); i++) {
                if (number.intValue() <= f.edges().get(i)) {
                    return f.levels().get(i);
                }
            }
            return f.levels().get(f.levels().size() - 1);
        }
        String text = String.valueOf(value);
        return f.levels().contains(text) ? text : f.reference();
    }
}
