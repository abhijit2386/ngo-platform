import 'package:flutter/material.dart';

/// Centralised design tokens (spec §8.2). Never hard-code colours in widgets.
abstract final class AppColors {
  static const primary = Color(0xFF087A4B);
  static const primaryDark = Color(0xFF064E3B);
  static const background = Color(0xFFFAFBF7);
  static const surface = Color(0xFFFFFFFF);
  static const text = Color(0xFF14213D);
  static const textMuted = Color(0xFF5B6478);
  static const growth = Color(0xFF1976D2);
  static const professional = Color(0xFF7437E8);
  static const enterprise = Color(0xFFE97716);
  static const error = Color(0xFFC62828);
  static const warning = Color(0xFFF59E0B);
  static const success = Color(0xFF087A4B);
  static const outline = Color(0xFFE3E7DF);
  static const primarySoft = Color(0xFFE6F3EC);

  static Color plan(String? code) => switch (code) {
        'GROWTH' => growth,
        'PROFESSIONAL' => professional,
        'ENTERPRISE' => enterprise,
        _ => primary,
      };
}

abstract final class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
}

abstract final class AppRadius {
  static const card = 16.0;
  static const button = 12.0;
  static const chip = 20.0;
}

/// Minimum accessible touch target (48dp, Material / WCAG guidance).
const kMinTouchTarget = 48.0;
