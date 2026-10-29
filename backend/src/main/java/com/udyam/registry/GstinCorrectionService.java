package com.udyam.registry;

import com.udyam.common.privacy.Masking;
import com.udyam.registry.GstinCorrection.Substitution;
import com.udyam.registry.validation.GstinValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registry-aware repair of OCR character confusions in a GSTIN.
 * Tries position-aware substitutions (max 2) and accepts a fix only if EXACTLY ONE candidate passes
 * format + checksum and exists in the registry. 5↔S and 8↔B are deliberately not tried, so a
 * tampered digit (e.g. 5→8) is never "corrected" back.
 */
@Service
public class GstinCorrectionService {

    public static final int MAX_SUBSTITUTIONS = 2;

    private static final Logger log = LoggerFactory.getLogger(GstinCorrectionService.class);

    private static final Map<Character, char[]> DIGIT_FIXES = Map.of('O', new char[] {'0'}, 'I', new char[] {'1'},
            'L', new char[] {'1'});
    private static final Map<Character, char[]> LETTER_FIXES = Map.of('0', new char[] {'O'}, '1', new char[] {'I'},
            'I', new char[] {'J'}, 'J', new char[] {'I'});
    private static final Map<Character, char[]> ALNUM_FIXES = Map.of('0', new char[] {'O'}, 'O', new char[] {'0'},
            '1', new char[] {'I'}, 'I', new char[] {'1'});
    private static final Map<Character, char[]> Z_FIXES = Map.of('2', new char[] {'Z'});

    private final GstinRegistryLookup registry;

    public GstinCorrectionService(GstinRegistryLookup registry) {
        this.registry = registry;
    }

    public GstinCorrection correct(String raw) {
        String gstin = GstinValidator.normalize(raw);
        if (GstinValidator.isValid(gstin) && registry.exists(gstin)) {
            return GstinCorrection.none(gstin);
        }
        if (gstin.length() != GstinValidator.LENGTH) {
            return GstinCorrection.none(gstin);
        }

        Map<String, List<Substitution>> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, List<Substitution>> c : generateCandidates(gstin).entrySet()) {
            if (GstinValidator.isValid(c.getKey())) {
                candidates.putIfAbsent(c.getKey(), c.getValue());
            }
        }
        if (candidates.isEmpty()) {
            return GstinCorrection.none(gstin);
        }

        Set<String> registered = registry.findExisting(candidates.keySet());
        if (registered.size() != 1) {
            log.info("GSTIN correction not applied for {}: {} registered candidates",
                    Masking.gstin(gstin), registered.size());
            return GstinCorrection.none(gstin);
        }

        String fixed = registered.iterator().next();
        List<Substitution> subs = candidates.get(fixed);
        log.info("GSTIN corrected {} -> {} ({} substitution(s))", Masking.gstin(gstin), Masking.gstin(fixed), subs.size());
        return new GstinCorrection(gstin, fixed, true, subs);
    }

    /** All strings reachable with 1..MAX_SUBSTITUTIONS position-aware substitutions, with the substitutions used. */
    static Map<String, List<Substitution>> generateCandidates(String gstin) {
        List<Substitution> options = new ArrayList<>();
        for (int pos = 0; pos < gstin.length(); pos++) {
            char[] alternatives = fixesFor(pos).get(gstin.charAt(pos));
            if (alternatives != null) {
                for (char to : alternatives) {
                    options.add(new Substitution(pos, gstin.charAt(pos), to));
                }
            }
        }

        Map<String, List<Substitution>> result = new LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
            Substitution a = options.get(i);
            result.putIfAbsent(apply(gstin, List.of(a)), List.of(a));
        }
        if (MAX_SUBSTITUTIONS >= 2) {
            for (int i = 0; i < options.size(); i++) {
                for (int j = i + 1; j < options.size(); j++) {
                    Substitution a = options.get(i);
                    Substitution b = options.get(j);
                    if (a.position() != b.position()) {
                        result.putIfAbsent(apply(gstin, List.of(a, b)), List.of(a, b));
                    }
                }
            }
        }
        return result;
    }

    private static Map<Character, char[]> fixesFor(int pos) {
        if (pos <= 1 || (pos >= 7 && pos <= 10)) {
            return DIGIT_FIXES;           // state code and PAN digits
        }
        if ((pos >= 2 && pos <= 6) || pos == 11) {
            return LETTER_FIXES;          // PAN letters
        }
        if (pos == 13) {
            return Z_FIXES;               // always 'Z'
        }
        return ALNUM_FIXES;               // 12 (entity number) and 14 (check character)
    }

    private static String apply(String gstin, List<Substitution> subs) {
        char[] chars = gstin.toCharArray();
        for (Substitution s : subs) {
            chars[s.position()] = s.to();
        }
        return new String(chars);
    }
}
