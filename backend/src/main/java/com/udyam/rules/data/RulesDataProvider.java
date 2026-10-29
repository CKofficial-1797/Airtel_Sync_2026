package com.udyam.rules.data;

/** Supplies the current RulesData snapshot. The app uses DbRulesDataProvider; tests pass {@code () -> data}. */
@FunctionalInterface
public interface RulesDataProvider {

    RulesData get();
}
