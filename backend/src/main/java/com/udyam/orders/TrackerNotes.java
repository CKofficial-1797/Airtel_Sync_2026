package com.udyam.orders;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Event notes the tracker turns back into customer-friendly text. */
public final class TrackerNotes {

    public static final String RESUMED = "Resumed after hold";

    private static final Pattern SLOT = Pattern.compile("^Slot (\\d{4}-\\d{2}-\\d{2}) (MORNING|AFTERNOON|EVENING)$");

    private TrackerNotes() {
    }

    public record SlotNote(LocalDate date, String slot) {
    }

    public static String slot(LocalDate date, String slot) {
        return "Slot " + date + " " + slot;
    }

    public static Optional<SlotNote> parseSlot(String note) {
        if (note == null) {
            return Optional.empty();
        }
        Matcher m = SLOT.matcher(note);
        return m.matches() ? Optional.of(new SlotNote(LocalDate.parse(m.group(1)), m.group(2))) : Optional.empty();
    }
}
