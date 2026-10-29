import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/session/preferences.dart';
import '../design_system/theme.dart';
import '../localization/app_localizations.dart';
import '../routing/router.dart';

class NgoApp extends ConsumerWidget {
  const NgoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final prefs = ref.watch(prefsProvider);
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      onGenerateTitle: (c) => L10n.of(c).appName,
      debugShowCheckedModeBanner: false,
      theme: buildTheme(highContrast: prefs.highContrast),
      locale: prefs.locale,
      supportedLocales: L10n.supportedLocales,
      localizationsDelegates: const [
        L10n.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      routerConfig: router,
    );
  }
}
