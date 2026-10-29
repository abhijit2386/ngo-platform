import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/session/session.dart';

/// Built-in avatars (a01..a16): simple icons on colour, for people who prefer not to show their face.
/// Original combinations of Material icons; the server stores only the code.
const avatarPresets = <String, (IconData, Color)>{
  'a01': (Icons.face, Color(0xFF0E7C66)),
  'a02': (Icons.face_2, Color(0xFF7C3AED)),
  'a03': (Icons.face_3, Color(0xFFDB2777)),
  'a04': (Icons.face_4, Color(0xFF2563EB)),
  'a05': (Icons.face_5, Color(0xFFEA580C)),
  'a06': (Icons.face_6, Color(0xFF0891B2)),
  'a07': (Icons.spa, Color(0xFF16A34A)),
  'a08': (Icons.local_florist, Color(0xFFBE185D)),
  'a09': (Icons.eco, Color(0xFF65A30D)),
  'a10': (Icons.wb_sunny, Color(0xFFD97706)),
  'a11': (Icons.star, Color(0xFF4F46E5)),
  'a12': (Icons.favorite, Color(0xFFDC2626)),
  'a13': (Icons.pets, Color(0xFF92400E)),
  'a14': (Icons.music_note, Color(0xFF9333EA)),
  'a15': (Icons.emoji_nature, Color(0xFF0D9488)),
  'a16': (Icons.volunteer_activism, Color(0xFF475569)),
};

typedef PhotoKey = ({String userId, int version, int size, bool self});

/// Photos are private: fetched with the user's token and cached per version (a new upload changes the version).
final photoBytesProvider = FutureProvider.family<Uint8List?, PhotoKey>((ref, k) async {
  ref.watch(sessionKeyProvider);
  final path = k.self ? '/api/v1/me/photo?size=${k.size}' : '/api/v1/users/${k.userId}/photo?size=${k.size}';
  try {
    return Uint8List.fromList(await ref.read(apiProvider).bytes(path));
  } catch (_) {
    return null; // fall back to initials
  }
});

/// Profile picture with name fallback: own photo, built-in avatar, or initials on a colour derived from the id.
class UserAvatar extends ConsumerWidget {
  const UserAvatar({super.key, required this.userId, required this.name, this.avatar, this.radius = 20, this.self = false});

  final String userId;
  final String name;
  final Map<String, dynamic>? avatar;
  final double radius;
  /// True for the signed-in user's own picture (uses /me/photo, allowed before the profile is complete).
  final bool self;

  static Color _colorFor(String id) {
    const palette = [Color(0xFF0E7C66), Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFEA580C), Color(0xFF0891B2)];
    return palette[id.hashCode.abs() % palette.length];
  }

  static String initials(String name) {
    final parts = name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    final first = parts.first.characters.first;
    final last = parts.length > 1 ? parts.last.characters.first : '';
    return (first + last).toUpperCase();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final kind = avatar?['kind'] as String? ?? 'NONE';
    Widget initialsAvatar() => CircleAvatar(
          radius: radius,
          backgroundColor: _colorFor(userId),
          child: Text(initials(name), style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: radius * 0.75)),
        );
    final Widget child;
    if (kind == 'PRESET' && avatarPresets.containsKey(avatar?['preset'])) {
      final (icon, color) = avatarPresets[avatar!['preset']]!;
      child = CircleAvatar(radius: radius, backgroundColor: color, child: Icon(icon, color: Colors.white, size: radius * 1.1));
    } else if (kind == 'PHOTO') {
      final px = radius * MediaQuery.devicePixelRatioOf(context) * 2 > 160 ? 512 : 128;
      final bytes = ref.watch(photoBytesProvider(
          (userId: userId, version: (avatar?['photoVersion'] as num?)?.toInt() ?? 0, size: px, self: self)));
      child = bytes.maybeWhen(
        data: (b) => b == null ? initialsAvatar() : CircleAvatar(radius: radius, backgroundImage: MemoryImage(b)),
        orElse: initialsAvatar,
      );
    } else {
      child = initialsAvatar();
    }
    return Semantics(label: name, image: true, child: ExcludeSemantics(child: child));
  }
}
