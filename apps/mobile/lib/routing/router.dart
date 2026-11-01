import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/auth/auth_service.dart';
import '../core/session/preferences.dart';
import '../core/session/session.dart';
import '../features/admin/admin_pages.dart';
import '../features/campaigns/campaign_pages.dart';
import '../features/auth/login_page.dart';
import '../features/donations/donation_pages.dart';
import '../features/donor/donor_pages.dart';
import '../features/ngo/home_page.dart';
import '../features/notifications/notification_pages.dart';
import '../features/profile/profile_pages.dart';
import '../features/ngo/more_page.dart';
import '../features/ngo/ngo_tabs.dart';
import '../features/onboarding/onboarding_pages.dart';
import '../features/registration/registration_pages.dart';
import '../features/settings/settings_pages.dart';
import '../features/staff/staff_pages.dart';
import '../features/subscription/subscription_pages.dart';
import '../features/accounts/expense_pages.dart';
import '../features/accounts/sheet_pages.dart';
import '../features/accounts/team_money_pages.dart';
import '../features/documents/document_pages.dart';
import '../features/meetings/meeting_pages.dart';
import 'shells.dart';

/// Bridges Riverpod state changes to GoRouter re-evaluation of redirects.
class _RouterRefresh extends ChangeNotifier {
  _RouterRefresh(Ref ref) {
    ref.listen(authStateProvider, (_, __) => notifyListeners());
    ref.listen(meProvider, (_, __) => notifyListeners());
    ref.listen(prefsProvider, (_, __) => notifyListeners());
  }
}

const _onboardingPaths = {'/welcome', '/language', '/user-type', '/intro', '/terms', '/permissions'};

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = _RouterRefresh(ref);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    initialLocation: '/splash',
    refreshListenable: refresh,
    redirect: (context, state) {
      final loc = state.matchedLocation;
      final prefs = ref.read(prefsProvider);
      final auth = ref.read(authStateProvider);
      if (!prefs.onboarded) {
        return _onboardingPaths.contains(loc) ? null : (prefs.locale == null ? '/language' : '/welcome');
      }
      if (auth.isLoading || auth.value == null) return loc == '/splash' ? null : '/splash';
      final status = auth.value!.status;
      if (status != AuthStatus.signedIn) return loc == '/login' ? null : '/login';
      final me = ref.read(meProvider);
      // While /me reloads after a sign-in, the previous value may be the signed-out `null`;
      // routing on it would send members to the "no NGO yet" page. Wait for the real profile.
      if (me.isLoading && me.value == null) return loc == '/splash' ? null : '/splash';
      if (me.hasError && !me.hasValue) return loc == '/offline' ? null : '/offline';
      final mode = prefs.mode ?? 'donor';
      final hasMembership = me.value?.activeMemberships.isNotEmpty ?? false;
      final platformStaff = me.value?.isPlatformSupport ?? false;
      // Platform admin/support without an NGO of their own start in the verification console.
      final home = mode == 'ngo'
          ? (hasMembership ? '/ngo/home' : (platformStaff ? '/admin/verifications' : '/ngo/start'))
          : '/donor/discover';
      // Adults-only rule: until the profile (with date of birth) is saved, the server answers PROFILE_INCOMPLETE to
      // everything else, so the app goes straight to the profile screen. Platform staff are exempt.
      if (me.value != null && !me.value!.profileComplete && !platformStaff) {
        return loc == '/profile/complete' ? null : '/profile/complete';
      }
      if (loc == '/profile/complete') return home;
      if (loc == '/splash' || loc == '/login' || loc == '/offline' || _onboardingPaths.contains(loc)) return home;
      // Switching between the donor and NGO apps only changes the saved mode; move to that app's home.
      if (mode == 'ngo' && loc.startsWith('/donor/')) return home;
      if (mode != 'ngo' && loc.startsWith('/ngo/')) return home;
      // Members who land on the start page via a stale redirect go to their NGO home.
      if (loc == '/ngo/start' && hasMembership && state.uri.queryParameters['stay'] != '1') return '/ngo/home';
      if (loc.startsWith('/ngo/') && !loc.startsWith('/ngo/start') && !loc.startsWith('/ngo/register') && !hasMembership) {
        return '/ngo/start';
      }
      if (loc.startsWith('/admin') && !(me.value?.isPlatformSupport ?? false)) return home;
      return null;
    },
    routes: [
      GoRoute(path: '/splash', builder: (_, __) => const SplashPage()),
      GoRoute(path: '/offline', builder: (_, __) => const OfflinePage()),
      GoRoute(path: '/language', builder: (_, __) => const LanguagePage()),
      GoRoute(path: '/welcome', builder: (_, __) => const WelcomePage()),
      GoRoute(path: '/user-type', builder: (_, __) => const UserTypePage()),
      GoRoute(path: '/intro', builder: (_, __) => const IntroPage()),
      GoRoute(path: '/terms', builder: (_, __) => const TermsPage()),
      GoRoute(path: '/permissions', builder: (_, __) => const PermissionsPage()),
      GoRoute(path: '/login', builder: (_, __) => const LoginPage()),
      GoRoute(path: '/ngo/start', builder: (_, __) => const NgoStartPage()),
      GoRoute(
        path: '/ngo/register/:ngoId',
        builder: (_, s) => RegistrationWizardPage(ngoId: s.pathParameters['ngoId']!),
      ),
      GoRoute(path: '/ngo/register', builder: (_, __) => const RegistrationWizardPage()),
      StatefulShellRoute.indexedStack(
        builder: (_, __, shell) => NgoShell(shell: shell),
        branches: [
          StatefulShellBranch(routes: [GoRoute(path: '/ngo/home', builder: (_, __) => const NgoHomePage())]),
          StatefulShellBranch(routes: [GoRoute(path: '/ngo/donors', builder: (_, __) => const DonorsTab())]),
          StatefulShellBranch(routes: [GoRoute(path: '/ngo/campaigns', builder: (_, __) => const CampaignsTab())]),
          StatefulShellBranch(routes: [GoRoute(path: '/ngo/reports', builder: (_, __) => const ReportsTab())]),
          StatefulShellBranch(routes: [GoRoute(path: '/ngo/more', builder: (_, __) => const MorePage())]),
        ],
      ),
      // Campaigns: 'new' must precede ':id'.
      GoRoute(path: '/ngo/campaigns/new', builder: (_, __) => const CampaignFormPage()),
      GoRoute(path: '/ngo/campaigns/:id', builder: (_, s) => CampaignDetailPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/campaigns/:id/edit', builder: (_, s) => CampaignFormPage(campaignId: s.pathParameters['id']!)),
      // Campaign accounts: 'new' must precede ':eid'.
      GoRoute(path: '/ngo/campaigns/:id/expenses', builder: (_, s) => CampaignExpensesPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/campaigns/:id/expenses/new', builder: (_, s) => ExpenseFormPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/campaigns/:id/expenses/:eid',
          builder: (_, s) => ExpenseDetailPage(campaignId: s.pathParameters['id']!, expenseId: s.pathParameters['eid']!)),
      GoRoute(path: '/ngo/campaigns/:id/expenses/:eid/edit',
          builder: (_, s) => ExpenseFormPage(campaignId: s.pathParameters['id']!, expenseId: s.pathParameters['eid']!)),
      GoRoute(path: '/ngo/campaigns/:id/budget', builder: (_, s) => BudgetPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/campaigns/:id/accounts', builder: (_, s) => AccountSheetPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/expenses/pending', builder: (_, __) => const PendingExpensesPage()),
      GoRoute(path: '/ngo/vendors', builder: (_, __) => const VendorsPage()),
      GoRoute(path: '/ngo/staff-money', builder: (_, __) => const StaffMoneyPage()),
      // Document vault: fixed paths before ':id'.
      GoRoute(path: '/ngo/documents', builder: (_, s) => DocumentsPage(initialCategory: s.uri.queryParameters['category'])),
      GoRoute(path: '/ngo/documents/upload', builder: (_, s) => DocumentUploadPage(category: s.uri.queryParameters['category'])),
      GoRoute(path: '/ngo/documents/bin', builder: (_, __) => const DocumentBinPage()),
      GoRoute(path: '/ngo/documents/:id', builder: (_, s) => DocumentDetailPage(documentId: s.pathParameters['id']!)),
      // Meetings and minutes.
      GoRoute(path: '/ngo/meetings', builder: (_, __) => const MeetingsPage()),
      GoRoute(path: '/ngo/meetings/new', builder: (_, __) => const MeetingFormPage()),
      GoRoute(path: '/ngo/meetings/:id', builder: (_, s) => MeetingDetailPage(meetingId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/meetings/:id/edit', builder: (_, s) => MeetingFormPage(meetingId: s.pathParameters['id']!)),
      GoRoute(path: '/ngo/action-items', builder: (_, __) => const ActionItemsPage()),
      GoRoute(path: '/ngo/staff-money/:uid', builder: (_, s) => StaffLedgerPage(userId: s.pathParameters['uid']!)),
      GoRoute(path: '/ngo/subscription', builder: (_, __) => const SubscriptionPage()),
      GoRoute(path: '/ngo/subscription/plans', builder: (_, __) => const PlansPage()),
      GoRoute(path: '/ngo/subscription/billing', builder: (_, __) => const BillingHistoryPage()),
      GoRoute(path: '/ngo/payments', builder: (_, __) => const PaymentSettingsPage()),
      GoRoute(path: '/ngo/staff', builder: (_, __) => const StaffPage()),
      GoRoute(path: '/ngo/profile', builder: (_, __) => const NgoProfilePage()),
      StatefulShellRoute.indexedStack(
        builder: (_, __, shell) => DonorShell(shell: shell),
        branches: [
          StatefulShellBranch(routes: [GoRoute(path: '/donor/discover', builder: (_, __) => const DiscoverPage())]),
          StatefulShellBranch(routes: [GoRoute(path: '/donor/donations', builder: (_, __) => const MyDonationsPage())]),
          StatefulShellBranch(routes: [GoRoute(path: '/donor/following', builder: (_, __) => const FollowingPage())]),
          StatefulShellBranch(routes: [GoRoute(path: '/donor/profile', builder: (_, __) => const DonorProfilePage())]),
        ],
      ),
      GoRoute(path: '/donor/campaigns', builder: (_, __) => const PublicCampaignsPage()),
      GoRoute(path: '/donor/campaign/:id', builder: (_, s) => PublicCampaignPage(campaignId: s.pathParameters['id']!)),
      GoRoute(path: '/donor/ngo/:id', builder: (_, s) => PublicNgoPage(ngoId: s.pathParameters['id']!)),
      GoRoute(path: '/settings', builder: (_, __) => const SettingsPage()),
      GoRoute(path: '/profile/complete', builder: (_, __) => const CompleteProfilePage()),
      GoRoute(path: '/profile', builder: (_, __) => const MyProfilePage()),
      GoRoute(path: '/notifications', builder: (_, __) => const NotificationsPage()),
      GoRoute(path: '/notifications/preferences', builder: (_, __) => const NotificationPreferencesPage()),
      GoRoute(path: '/notifications/history', builder: (_, __) => const CommunicationHistoryPage()),
      GoRoute(path: '/admin/verifications', builder: (_, __) => const VerificationQueuePage()),
      GoRoute(path: '/admin/ngos/:id', builder: (_, s) => VerificationDetailPage(ngoId: s.pathParameters['id']!)),
    ],
  );
});
