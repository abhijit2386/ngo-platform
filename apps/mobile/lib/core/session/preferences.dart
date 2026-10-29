import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Non-sensitive device preferences (language, onboarding progress, chosen experience).
class Prefs {
  Prefs({this.locale, this.onboarded = false, this.mode, this.ngoId, this.termsVersion, this.highContrast = false});
  final Locale? locale;
  final bool onboarded;
  final String? mode; // 'ngo' | 'donor'
  final String? ngoId;
  final String? termsVersion;
  final bool highContrast;

  Prefs copyWith({Locale? locale, bool? onboarded, String? mode, String? ngoId, String? termsVersion, bool? highContrast,
      bool clearNgo = false}) {
    return Prefs(
      locale: locale ?? this.locale,
      onboarded: onboarded ?? this.onboarded,
      mode: mode ?? this.mode,
      ngoId: clearNgo ? null : (ngoId ?? this.ngoId),
      termsVersion: termsVersion ?? this.termsVersion,
      highContrast: highContrast ?? this.highContrast,
    );
  }
}

const currentTermsVersion = '2026-10';

final sharedPrefsProvider = Provider<SharedPreferences>((_) => throw UnimplementedError('overridden in main'));

final prefsProvider = NotifierProvider<PrefsNotifier, Prefs>(PrefsNotifier.new);

class PrefsNotifier extends Notifier<Prefs> {
  SharedPreferences get _sp => ref.read(sharedPrefsProvider);

  @override
  Prefs build() {
    final lang = _sp.getString('locale');
    return Prefs(
      locale: lang == null ? null : Locale(lang),
      onboarded: _sp.getBool('onboarded') ?? false,
      mode: _sp.getString('mode'),
      ngoId: _sp.getString('ngoId'),
      termsVersion: _sp.getString('termsVersion'),
      highContrast: _sp.getBool('highContrast') ?? false,
    );
  }

  Future<void> setLocale(Locale l) async {
    await _sp.setString('locale', l.languageCode);
    state = state.copyWith(locale: l);
  }

  Future<void> setMode(String mode) async {
    await _sp.setString('mode', mode);
    state = state.copyWith(mode: mode);
  }

  Future<void> acceptTerms() async {
    await _sp.setString('termsVersion', currentTermsVersion);
    state = state.copyWith(termsVersion: currentTermsVersion);
  }

  Future<void> completeOnboarding() async {
    await _sp.setBool('onboarded', true);
    state = state.copyWith(onboarded: true);
  }

  Future<void> selectNgo(String? id) async {
    if (id == null) {
      await _sp.remove('ngoId');
      state = state.copyWith(clearNgo: true);
    } else {
      await _sp.setString('ngoId', id);
      state = state.copyWith(ngoId: id);
    }
  }

  Future<void> setHighContrast(bool v) async {
    await _sp.setBool('highContrast', v);
    state = state.copyWith(highContrast: v);
  }

  Future<void> resetForSignOut() async {
    await _sp.remove('ngoId');
    state = state.copyWith(clearNgo: true);
  }
}
