import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_client.dart';
import '../auth/auth_service.dart';
import 'preferences.dart';

class Membership {
  Membership(this.json);
  final Map<String, dynamic> json;
  String get ngoId => json['ngoId'] as String;
  String get membershipId => json['membershipId'] as String;
  String get role => json['role'] as String;
  String get status => json['status'] as String;
  Set<String> get permissions => {...(json['permissions'] as List? ?? const []).map((e) => '$e')};
  bool can(String p) => status == 'ACTIVE' && permissions.contains(p);
  bool get isAdmin => role == 'PRIMARY_ADMIN' || role == 'SECONDARY_ADMIN';
}

class Me {
  Me(this.json);
  final Map<String, dynamic> json;
  String get userId => json['userId'] as String;
  String get email => json['email'] as String? ?? '';
  String get name => (json['displayName'] as String?)?.trim().isNotEmpty == true ? json['displayName'] as String : email;
  bool get emailVerified => json['emailVerified'] == true;
  Set<String> get platformRoles => {...(json['platformRoles'] as List? ?? const []).map((e) => '$e')};
  bool get isPlatformAdmin => platformRoles.contains('PLATFORM_ADMIN');
  bool get isPlatformSupport => isPlatformAdmin || platformRoles.contains('PLATFORM_SUPPORT');
  List<Membership> get memberships =>
      (json['memberships'] as List? ?? const []).map((e) => Membership(Map<String, dynamic>.from(e as Map))).toList();
  List<Membership> get activeMemberships => memberships.where((m) => m.status == 'ACTIVE').toList();
  String? get phone => json['phone'] as String?;
  String get language => json['preferredLanguage'] as String? ?? 'en';
  String? get displayName => json['displayName'] as String?;
  /// ISO date (yyyy-MM-dd) or null until the profile is completed.
  String? get birthDate => json['birthDate'] as String?;
  String get gender => json['gender'] as String? ?? 'UNDISCLOSED';
  Map<String, dynamic>? get avatar => (json['avatar'] as Map?)?.cast<String, dynamic>();
  /// Adults-only gate: false until the profile (with an 18+ date of birth) is saved.
  bool get profileComplete => json['profileComplete'] != false;
  String get accountStatus => json['status'] as String? ?? 'ACTIVE';
  bool get phoneVerified => json['phoneVerified'] == true;
  /// NGO team members must have a verified mobile (server gate).
  bool get phoneRequired => json['phoneRequired'] == true;
}

/// Identity of the signed-in user (token subject). Every provider that caches user-specific data watches this,
/// so signing out or switching accounts can never show the previous user's data (shared-device privacy).
final sessionKeyProvider = Provider<String?>((ref) {
  final auth = ref.watch(authStateProvider).value;
  return auth != null && auth.signedIn ? auth.claims['sub'] as String? : null;
});

/// The signed-in user's profile and NGO memberships (GET /api/v1/me).
final meProvider = FutureProvider<Me?>((ref) async {
  final auth = await ref.watch(authStateProvider.future);
  if (!auth.signedIn) return null;
  final json = await ref.read(apiProvider).getJson('/api/v1/me');
  return Me(json);
});

/// Membership in the currently selected NGO (null when acting as a donor or not a member).
final currentMembershipProvider = Provider<Membership?>((ref) {
  final me = ref.watch(meProvider).value;
  final ngoId = ref.watch(prefsProvider).ngoId;
  if (me == null) return null;
  final active = me.activeMemberships;
  if (active.isEmpty) return null;
  return active.firstWhere((m) => m.ngoId == ngoId, orElse: () => active.first);
});

final currentNgoIdProvider = Provider<String?>((ref) => ref.watch(currentMembershipProvider)?.ngoId);
