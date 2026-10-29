import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/session/preferences.dart';
import '../../core/session/session.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';

/// Brand mark used on splash/welcome (original artwork: hands forming a bridge).
class BrandMark extends StatelessWidget {
  const BrandMark({super.key, this.size = 88});
  final double size;
  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        gradient: const LinearGradient(colors: [AppColors.primary, AppColors.primaryDark], begin: Alignment.topLeft, end: Alignment.bottomRight),
        borderRadius: BorderRadius.circular(size * 0.28),
        boxShadow: [BoxShadow(color: AppColors.primary.withValues(alpha: 0.25), blurRadius: 24, offset: const Offset(0, 10))],
      ),
      child: Icon(Icons.volunteer_activism, color: Colors.white, size: size * 0.55),
    );
  }
}

/// APP-001
class SplashPage extends StatelessWidget {
  const SplashPage({super.key});
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(
      body: Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const BrandMark(size: 96),
          const SizedBox(height: AppSpacing.lg),
          Text(t.appName, style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: AppSpacing.sm),
          Text(t.tagline, style: const TextStyle(color: AppColors.textMuted)),
          const SizedBox(height: AppSpacing.xl),
          const SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3)),
        ]),
      ),
    );
  }
}

/// APP-012/013: the backend could not be reached while loading the session.
class OfflinePage extends ConsumerWidget {
  const OfflinePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    return Scaffold(
      body: SafeArea(child: ErrorState(message: t.noInternet, retryLabel: t.retry, onRetry: () => ref.invalidate(meProvider))),
    );
  }
}

/// APP-003
class LanguagePage extends ConsumerWidget {
  const LanguagePage({super.key});
  static const options = [('en', 'English', 'English'), ('hi', 'हिन्दी', 'Hindi'), ('mr', 'मराठी', 'Marathi')];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final current = ref.watch(prefsProvider).locale?.languageCode ?? Localizations.localeOf(context).languageCode;
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const SizedBox(height: AppSpacing.xl),
            const Center(child: BrandMark(size: 72)),
            const SizedBox(height: AppSpacing.lg),
            Text(t.chooseLanguage, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.sm),
            Text(t.languageHint, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: AppSpacing.lg),
            for (final o in options)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: AppCard(
                  color: current == o.$1 ? AppColors.primarySoft : null,
                  onTap: () => ref.read(prefsProvider.notifier).setLocale(Locale(o.$1)),
                  child: Row(children: [
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(o.$2, style: Theme.of(context).textTheme.titleMedium),
                      Text(o.$3, style: const TextStyle(color: AppColors.textMuted)),
                    ])),
                    Icon(current == o.$1 ? Icons.radio_button_checked : Icons.radio_button_off, color: AppColors.primary),
                  ]),
                ),
              ),
            const Spacer(),
            FilledButton(
              onPressed: () async {
                if (ref.read(prefsProvider).locale == null) await ref.read(prefsProvider.notifier).setLocale(Locale(current));
                if (context.mounted) context.go('/welcome');
              },
              child: Text(t.continueLabel),
            ),
          ]),
        ),
      ),
    );
  }
}

/// APP-002
class WelcomePage extends StatelessWidget {
  const WelcomePage({super.key});
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Spacer(),
            const _HeroIllustration(),
            const SizedBox(height: AppSpacing.xl),
            Text(t.welcomeTitle, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.md),
            Text(t.welcomeBody, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted, fontSize: 16)),
            const Spacer(),
            FilledButton(onPressed: () => context.go('/user-type'), child: Text(t.getStarted)),
            const SizedBox(height: AppSpacing.sm),
            TextButton(onPressed: () => context.go('/language'), child: Text(t.language)),
          ]),
        ),
      ),
    );
  }
}

class _HeroIllustration extends StatelessWidget {
  const _HeroIllustration();
  @override
  Widget build(BuildContext context) {
    Widget bubble(IconData icon, Color c) => Container(
          width: 64,
          height: 64,
          decoration: BoxDecoration(color: c.withValues(alpha: 0.12), shape: BoxShape.circle),
          child: Icon(icon, color: c, size: 30),
        );
    return SizedBox(
      height: 200,
      child: Stack(alignment: Alignment.center, children: [
        Container(width: 190, height: 190, decoration: const BoxDecoration(color: AppColors.primarySoft, shape: BoxShape.circle)),
        const BrandMark(size: 96),
        Positioned(left: 20, top: 10, child: bubble(Icons.school, AppColors.growth)),
        Positioned(right: 20, top: 24, child: bubble(Icons.favorite, AppColors.error)),
        Positioned(left: 36, bottom: 6, child: bubble(Icons.receipt_long, AppColors.professional)),
        Positioned(right: 30, bottom: 0, child: bubble(Icons.groups, AppColors.enterprise)),
      ]),
    );
  }
}

/// APP-004
class UserTypePage extends ConsumerWidget {
  const UserTypePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    Widget option(String mode, IconData icon, String title, String sub, Color color) => Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.md),
          child: AppCard(
            onTap: () async {
              await ref.read(prefsProvider.notifier).setMode(mode);
              if (context.mounted) context.go('/intro');
            },
            child: Row(children: [
              CircleAvatar(radius: 28, backgroundColor: color.withValues(alpha: 0.12), child: Icon(icon, color: color, size: 28)),
              const SizedBox(width: AppSpacing.md),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(sub, style: const TextStyle(color: AppColors.textMuted)),
              ])),
              const Icon(Icons.chevron_right),
            ]),
          ),
        );
    return Scaffold(
      appBar: AppBar(),
      body: ListView(padding: const EdgeInsets.all(AppSpacing.lg), children: [
        Text(t.howWillYouUse, style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: AppSpacing.lg),
        option('ngo', Icons.apartment, t.iRunNgo, t.iRunNgoSub, AppColors.primary),
        option('donor', Icons.volunteer_activism, t.iDonate, t.iDonateSub, AppColors.enterprise),
      ]),
    );
  }
}

/// APP-005 / APP-006
class IntroPage extends ConsumerWidget {
  const IntroPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngo = ref.watch(prefsProvider).mode == 'ngo';
    final points = ngo
        ? [(Icons.receipt_long, t.ngoIntro1), (Icons.campaign, t.ngoIntro2), (Icons.workspace_premium, t.ngoIntro3)]
        : [(Icons.verified, t.donorIntro1), (Icons.account_balance, t.donorIntro2), (Icons.folder_shared, t.donorIntro3)];
    return Scaffold(
      appBar: AppBar(),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ngo ? t.ngoIntroTitle : t.donorIntroTitle, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: AppSpacing.lg),
            for (final p in points)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.md),
                child: Row(children: [
                  CircleAvatar(backgroundColor: AppColors.primarySoft, child: Icon(p.$1, color: AppColors.primary)),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: Text(p.$2, style: const TextStyle(fontSize: 16))),
                ]),
              ),
            const Spacer(),
            FilledButton(onPressed: () => context.go('/terms'), child: Text(t.continueLabel)),
          ]),
        ),
      ),
    );
  }
}

/// APP-007 / APP-008
class TermsPage extends ConsumerStatefulWidget {
  const TermsPage({super.key});
  @override
  ConsumerState<TermsPage> createState() => _TermsPageState();
}

class _TermsPageState extends ConsumerState<TermsPage> {
  bool _accepted = false;
  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(t.termsTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Expanded(child: SingleChildScrollView(child: Text(t.termsBody, style: const TextStyle(fontSize: 16, height: 1.5)))),
            CheckboxListTile(
              value: _accepted,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              onChanged: (v) => setState(() => _accepted = v ?? false),
              title: Text(t.acceptTerms),
            ),
            const SizedBox(height: AppSpacing.sm),
            FilledButton(
              onPressed: _accepted
                  ? () async {
                      await ref.read(prefsProvider.notifier).acceptTerms();
                      if (context.mounted) context.go('/permissions');
                    }
                  : null,
              child: Text(t.continueLabel),
            ),
          ]),
        ),
      ),
    );
  }
}

/// APP-009: explains why permissions are requested before the OS prompt.
class PermissionsPage extends ConsumerWidget {
  const PermissionsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    Future<void> finish() => ref.read(prefsProvider.notifier).completeOnboarding();
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Spacer(),
            const Center(child: CircleAvatar(radius: 48, backgroundColor: AppColors.primarySoft,
                child: Icon(Icons.notifications_active_outlined, size: 48, color: AppColors.primary))),
            const SizedBox(height: AppSpacing.lg),
            Text(t.permissionsTitle, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.md),
            Text(t.permissionsBody, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted, fontSize: 16)),
            const Spacer(),
            FilledButton(onPressed: finish, child: Text(t.notificationsAllow)),
            TextButton(onPressed: finish, child: Text(t.notNow)),
          ]),
        ),
      ),
    );
  }
}
