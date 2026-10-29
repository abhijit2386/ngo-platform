import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';

/// Current NGO's private profile.
final currentNgoProvider = FutureProvider<Map<String, dynamic>?>((ref) async {
  ref.watch(sessionKeyProvider);
  final id = ref.watch(currentNgoIdProvider);
  if (id == null) return null;
  return ref.read(apiProvider).getJson('/api/v1/ngos/$id');
});

/// Subscription of the current NGO; null until the NGO is verified (trial starts on approval).
final subscriptionProvider = FutureProvider<Map<String, dynamic>?>((ref) async {
  ref.watch(sessionKeyProvider);
  final id = ref.watch(currentNgoIdProvider);
  if (id == null) return null;
  try {
    return await ref.read(apiProvider).getJson('/api/v1/ngos/$id/subscription');
  } catch (e) {
    return null;
  }
});

final planCatalogueProvider = FutureProvider<Map<String, dynamic>>(
    (ref) => ref.read(apiProvider).getJson('/api/v1/public/plans'));
