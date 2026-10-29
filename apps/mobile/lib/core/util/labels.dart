import '../../localization/app_localizations.dart';

/// Human-readable labels for backend enum codes. Falls back to a title-cased code.
String documentTypeLabel(L10n t, String? code) => switch (code) {
      'REGISTRATION_CERTIFICATE' => t.docRegistrationCertificate,
      'ADDRESS_PROOF' => t.docAddressProof,
      'SIGNATORY_ID' => t.docSignatoryId,
      'PAN_CARD' => t.docPanCard,
      'CERTIFICATE_80G' => t.doc80g,
      'TRUST_DEED_OR_MOA' => t.docTrustDeed,
      'CERTIFICATE_12A' => t.doc12a,
      'LOGO' => t.docLogo,
      _ => humanizeCode(code),
    };

String registrationTypeLabel(L10n t, String? code) => switch (code) {
      'PUBLIC_TRUST' => t.typePublicTrust,
      'SOCIETY' => t.typeSociety,
      'SECTION_8_COMPANY' => t.typeSection8,
      _ => humanizeCode(code),
    };

/// PRIMARY_CONTACT -> "Primary contact".
String humanizeCode(String? code) {
  if (code == null || code.isEmpty) return '—';
  final s = code.replaceAll('_', ' ').toLowerCase();
  return s[0].toUpperCase() + s.substring(1);
}
