import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../localization/app_localizations.dart';

/// NGO bottom navigation: Home, Donors, Campaigns, Reports, More (spec §8.4).
class NgoShell extends StatelessWidget {
  const NgoShell({super.key, required this.shell});
  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home_outlined), selectedIcon: const Icon(Icons.home), label: t.navHome),
          NavigationDestination(icon: const Icon(Icons.people_outline), selectedIcon: const Icon(Icons.people), label: t.navDonors),
          NavigationDestination(icon: const Icon(Icons.campaign_outlined), selectedIcon: const Icon(Icons.campaign), label: t.navCampaigns),
          NavigationDestination(icon: const Icon(Icons.insights_outlined), selectedIcon: const Icon(Icons.insights), label: t.navReports),
          NavigationDestination(icon: const Icon(Icons.menu), label: t.navMore),
        ],
      ),
    );
  }
}

/// Donor bottom navigation: Discover, Donations, Following, Profile (spec §8.4).
class DonorShell extends StatelessWidget {
  const DonorShell({super.key, required this.shell});
  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.explore_outlined), selectedIcon: const Icon(Icons.explore), label: t.navDiscover),
          NavigationDestination(icon: const Icon(Icons.volunteer_activism_outlined), selectedIcon: const Icon(Icons.volunteer_activism), label: t.navDonations),
          NavigationDestination(icon: const Icon(Icons.favorite_border), selectedIcon: const Icon(Icons.favorite), label: t.navFollowing),
          NavigationDestination(icon: const Icon(Icons.person_outline), selectedIcon: const Icon(Icons.person), label: t.navProfile),
        ],
      ),
    );
  }
}
