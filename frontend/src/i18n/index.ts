import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { KEYS, readStorage, writeStorage } from "../lib/storage";
import en from "./en.json";
import hi from "./hi.json";

export type Language = "en" | "hi";

export function storedLanguage(): Language {
  return readStorage(KEYS.language) === "hi" ? "hi" : "en";
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, hi: { translation: hi } },
  lng: storedLanguage(),
  fallbackLng: "en",
  interpolation: { escapeValue: false }, // React already escapes
});

function applyLanguage(lng: string): void {
  document.documentElement.lang = lng;
}

applyLanguage(i18n.language);
i18n.on("languageChanged", (lng) => {
  applyLanguage(lng);
  writeStorage(KEYS.language, lng);
});

export function currentLanguage(): Language {
  return i18n.language === "hi" ? "hi" : "en";
}

export function setLanguage(language: Language): void {
  void i18n.changeLanguage(language);
}

export default i18n;
