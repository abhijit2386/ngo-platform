import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/auth/auth_service.dart';
import '../../core/config/app_config.dart';
import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../profile/avatar.dart';
import '../onboarding/onboarding_pages.dart';

/// SET-002..017 (core subset): profile, language, accessibility, account deletion, sign out.
class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final prefs = ref.watch(prefsProvider);
    final me = ref.watch(meProvider).value;
    return Scaffold(
      appBar: AppBar(title: Text(t.settings)),
      body: ListView(children: [
        if (me != null)
          ListTile(
            leading: UserAvatar(userId: me.userId, name: me.name, avatar: me.avatar, self: true),
            title: Text(me.name),
            subtitle: Text(me.email),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/profile'),
          ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.translate),
          title: Text(t.language),
          subtitle: Text(LanguagePage.options.firstWhere((o) => o.$1 == (prefs.locale?.languageCode ?? 'en'), orElse: () => LanguagePage.options.first).$2),
          onTap: () => showModalBottomSheet(
            context: context,
            builder: (c) => SafeArea(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                for (final o in LanguagePage.options)
                  ListTile(
                    title: Text(o.$2),
                    subtitle: Text(o.$3),
                    trailing: prefs.locale?.languageCode == o.$1 ? const Icon(Icons.check, color: AppColors.primary) : null,
                    onTap: () async {
                      await ref.read(prefsProvider.notifier).setLocale(Locale(o.$1));
                      if (c.mounted) Navigator.pop(c);
                    },
                  ),
              ]),
            ),
          ),
        ),
        ListTile(
          leading: const Icon(Icons.notifications_outlined),
          title: Text(t.notificationPreferences),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => context.push('/notifications/preferences'),
        ),
        SwitchListTile(
          secondary: const Icon(Icons.contrast),
          title: Text(t.highContrast),
          value: prefs.highContrast,
          onChanged: (v) => ref.read(prefsProvider.notifier).setHighContrast(v),
        ),
        ListTile(
          leading: const Icon(Icons.swap_horiz),
          title: Text(prefs.mode == 'ngo' ? t.switchToDonor : t.switchToNgo),
          onTap: () {
            ref.read(prefsProvider.notifier).setMode(prefs.mode == 'ngo' ? 'donor' : 'ngo');
            Navigator.of(context).pop();
          },
        ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.delete_outline, color: AppColors.error),
          title: Text(t.deleteAccount),
          subtitle: me?.json['status'] == 'DELETION_REQUESTED' ? Text(t.requested) : null,
          onTap: () async {
            if (!await confirm(context, title: t.deleteAccount, message: t.deleteAccountBody, destructive: true)) return;
            try {
              await ref.read(apiProvider).post('/api/v1/me/deletion-request');
              ref.invalidate(meProvider);
              if (context.mounted) showSnack(context, t.requested);
            } catch (e) {
              if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
            }
          },
        ),
        if (ref.watch(meProvider).value?.isPlatformSupport ?? false)
          ListTile(
            leading: const Icon(Icons.admin_panel_settings),
            title: Text(t.platformAdmin),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/admin/verifications'),
          ),
        ListTile(
          leading: const Icon(Icons.logout),
          title: Text(t.signOut),
          onTap: () async {
            await ref.read(prefsProvider.notifier).resetForSignOut();
            await ref.read(authServiceProvider).signOut();
          },
        ),
        const Divider(),
        ListTile(leading: const Icon(Icons.info_outline), title: Text(t.about), subtitle: Text('${t.appName} · ${t.version} 0.1.0 (${AppConfig.appBuild})')),
      ]),
    );
  }
}
