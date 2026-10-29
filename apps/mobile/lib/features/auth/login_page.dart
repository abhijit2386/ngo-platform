import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_service.dart';
import '../../core/session/preferences.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../onboarding/onboarding_pages.dart';

/// AUTH-001/002/003/010: sign-in and registration happen on the identity provider's page (PKCE);
/// the app never sees passwords.
class LoginPage extends ConsumerWidget {
  const LoginPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final auth = ref.watch(authStateProvider).value;
    final mode = ref.watch(prefsProvider).mode;
    Future<void> go({bool register = false}) async {
      try {
        await ref.read(authServiceProvider).signIn(register: register);
      } catch (e, st) {
        // Keep the cause in the device log (no tokens or credentials are included in AppAuth errors).
        debugPrint('Sign-in failed: $e\n$st');
        if (context.mounted) showSnack(context, t.signInFailed, error: true);
      }
    }

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Spacer(),
            const Center(child: BrandMark(size: 88)),
            const SizedBox(height: AppSpacing.lg),
            Text(t.signInTitle, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.sm),
            Text(t.signInSubtitle, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
            if (auth?.status == AuthStatus.sessionExpired) ...[
              const SizedBox(height: AppSpacing.md),
              Card(
                color: AppColors.warning.withValues(alpha: 0.12),
                child: Padding(padding: const EdgeInsets.all(12), child: Text(t.sessionExpired)),
              ),
            ],
            const Spacer(),
            BusyButton(label: t.createAccount, icon: Icons.person_add_alt_1, onPressed: () => go(register: true)),
            const SizedBox(height: AppSpacing.sm),
            BusyButton(label: t.signIn, icon: Icons.login, outlined: true, onPressed: () => go()),
            const SizedBox(height: AppSpacing.md),
            TextButton(
              onPressed: () => ref.read(prefsProvider.notifier).setMode(mode == 'ngo' ? 'donor' : 'ngo'),
              child: Text(mode == 'ngo' ? t.switchToDonor : t.switchToNgo),
            ),
          ]),
        ),
      ),
    );
  }
}
