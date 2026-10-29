import '../../core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';

final referenceDataProvider = FutureProvider<Map<String, dynamic>>(
    (ref) => ref.read(apiProvider).getJson('/api/v1/public/reference'));

final myRegistrationsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final list = await ref.read(apiProvider).getList('/api/v1/ngos/mine');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final myInvitationsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final list = await ref.read(apiProvider).getList('/api/v1/me/invitations');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final ngoProvider = FutureProvider.family<Map<String, dynamic>, String>(
    (ref, id) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$id');
});

final ngoDocumentsProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, id) async {
  ref.watch(sessionKeyProvider);
  final list = await ref.read(apiProvider).getList('/api/v1/ngos/$id/documents');
  return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});
