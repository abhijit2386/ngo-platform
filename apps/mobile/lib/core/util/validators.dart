import '../../localization/app_localizations.dart';

/// Client-side validation mirrors backend DTO rules (docs/testing/VALIDATION_MATRIX.md). The server re-validates.
class V {
  V(this.t);
  final L10n t;

  static final email = RegExp(r"^[^\s@]+@[^\s@]+\.[^\s@]{2,}$");
  static final phone = RegExp(r'^[6-9]\d{9}$');
  static final pin = RegExp(r'^[1-9]\d{5}$');
  static final pan = RegExp(r'^[A-Za-z]{5}\d{4}[A-Za-z]$');
  static final darpan = RegExp(r'^[A-Za-z]{2}/\d{4}/\d{7}$');
  static final name = RegExp(r"^[\p{L}\p{M}\p{N} .,'&()\-/]+$", unicode: true);

  String? required(String? v) => (v == null || v.trim().isEmpty) ? t.required : null;

  String? Function(String?) text({int min = 0, int max = 200, bool optional = false, RegExp? pattern, String? patternMessage}) {
    return (v) {
      final s = v?.trim() ?? '';
      if (s.isEmpty) return optional ? null : t.required;
      if (s.length < min) return t.tooShort(min);
      if (s.length > max) return '≤ $max';
      if (pattern != null && !pattern.hasMatch(s)) return patternMessage ?? t.required;
      return null;
    };
  }

  String? Function(String?) emailField({bool optional = false}) =>
      (v) => (v == null || v.trim().isEmpty) ? (optional ? null : t.required) : (email.hasMatch(v.trim()) ? null : t.invalidEmail);

  String? Function(String?) phoneField({bool optional = false}) =>
      (v) => (v == null || v.trim().isEmpty) ? (optional ? null : t.required) : (phone.hasMatch(v.trim()) ? null : t.invalidPhone);

  String? pinField(String? v) => (v == null || v.trim().isEmpty) ? t.required : (pin.hasMatch(v.trim()) ? null : t.invalidPin);

  String? panField(String? v) => (v == null || v.trim().isEmpty) ? null : (pan.hasMatch(v.trim()) ? null : t.invalidPan);

  String? darpanField(String? v) => (v == null || v.trim().isEmpty) ? null : (darpan.hasMatch(v.trim()) ? null : t.invalidDarpan);

  /// Rupee amount: positive, max 2 decimals, within bounds. Parsed as string, never double arithmetic for money.
  String? Function(String?) amount({required num min, required num max}) {
    return (v) {
      final s = v?.trim() ?? '';
      if (s.isEmpty) return t.required;
      if (!RegExp(r'^\d{1,9}(\.\d{1,2})?$').hasMatch(s)) return t.invalidAmount;
      final n = num.parse(s);
      if (n < min || n > max) return t.amountRange('$min', '$max');
      return null;
    };
  }
}
