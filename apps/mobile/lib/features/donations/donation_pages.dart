import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_error.dart';
import '../../core/session/session.dart';
import '../../core/util/validators.dart';
import '../../design_system/components.dart';
import '../../design_system/tokens.dart';
import '../../localization/app_localizations.dart';
import '../campaigns/campaign_api.dart';
import '../receipts/receipts_api.dart' show myReceiptsProvider;

/// Donor's last 80G details (their own data) for prefilling.
final taxProfileProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  ref.watch(sessionKeyProvider);
  try {
    return await ref.read(apiProvider).getJson('/api/v1/donations/tax-profile');
  } catch (_) {
    return const {};
  }
});

/// Public NGO facts used by the donate sheet (80G eligibility, name).
final donateNgoProvider = FutureProvider.family<Map<String, dynamic>, String>(
    (ref, id) => ref.read(apiProvider).getJson('/api/v1/public/ngos/$id'));

/// Opens the donate sheet; returns after the whole payment flow (sheet -> payment -> result) is finished.
Future<void> startDonation(BuildContext context, {required String ngoId, String? campaignId, required String title}) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => DonateSheet(ngoId: ngoId, campaignId: campaignId, title: title),
  );
}

/// DAPP-009/010: amount, optional 80G details, then checkout. Money goes straight to the NGO's own payment account.
class DonateSheet extends ConsumerStatefulWidget {
  const DonateSheet({super.key, required this.ngoId, this.campaignId, required this.title});
  final String ngoId;
  final String? campaignId;
  final String title;
  @override
  ConsumerState<DonateSheet> createState() => _DonateSheetState();
}

class _DonateSheetState extends ConsumerState<DonateSheet> {
  static const presets = [500, 1000, 2500, 5000];
  final _key = GlobalKey<FormState>();
  final _amount = TextEditingController(text: '1000');
  final _pan = TextEditingController();
  final _address = TextEditingController();
  /// One key per sheet: retries after a network error never create a second donation.
  final _idempotencyKey = newIdempotencyKey();
  bool _want80g = false;
  bool _prefilled = false;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_amount, _pan, _address]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _continue() async {
    final t = L10n.of(context);
    if (!_key.currentState!.validate()) return;
    final me = ref.read(meProvider).value;
    setState(() => _busy = true);
    try {
      final r = await ref.read(apiProvider).post('/api/v1/donations', idempotencyKey: _idempotencyKey, body: {
        'ngoId': widget.ngoId,
        if (widget.campaignId != null) 'campaignId': widget.campaignId,
        'amount': _amount.text.trim(),
        'donorName': me?.name ?? '',
        'donorEmail': me?.email,
        'wants80g': _want80g,
        if (_want80g) 'donorPan': _pan.text.trim().toUpperCase(),
        if (_want80g) 'donorAddress': _address.text.trim(),
      });
      if (!mounted) return;
      final nav = Navigator.of(context);
      nav.pop(); // close the sheet
      if (r['mockCheckout'] == true) {
        await nav.push(MaterialPageRoute(builder: (_) => TestPaymentPage(checkout: r, title: widget.title, ngoId: widget.ngoId,
            campaignId: widget.campaignId)));
      } else {
        // Real provider checkout (Razorpay) plugs in here once the NGO's merchant account is connected.
        showSnack(nav.context, t.donateComingSoon);
      }
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final v = V(t);
    final ngo = ref.watch(donateNgoProvider(widget.ngoId)).value;
    final can80g = ngo?['tax80gEligible'] == true;
    final tax = ref.watch(taxProfileProvider).value;
    if (!_prefilled && tax != null && tax.isNotEmpty) {
      _pan.text = '${tax['pan'] ?? ''}';
      _address.text = '${tax['address'] ?? ''}';
      _prefilled = true;
    }
    final me = ref.watch(meProvider).value;
    final selected = num.tryParse(_amount.text.trim());
    return Padding(
      padding: EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, MediaQuery.viewInsetsOf(context).bottom + AppSpacing.lg),
      child: Form(
        key: _key,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(t.donateTo, style: const TextStyle(color: AppColors.textMuted)),
            Text(widget.title, style: Theme.of(context).textTheme.titleLarge, maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: AppSpacing.md),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final p in presets)
                ChoiceChip(
                  label: Text(formatInr(p, whole: true)),
                  selected: selected == p,
                  onSelected: (_) => setState(() => _amount.text = '$p'),
                ),
            ]),
            const SizedBox(height: AppSpacing.sm),
            TextFormField(
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
              decoration: InputDecoration(labelText: t.amountRupees, prefixText: '₹ '),
              validator: v.amount(min: 10, max: 1000000),
              onChanged: (_) => setState(() {}),
            ),
            if (can80g) ...[
              const SizedBox(height: AppSpacing.sm),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _want80g,
                onChanged: (x) => setState(() => _want80g = x),
                title: Text(t.want80g),
                subtitle: Text(t.want80gHint),
              ),
              if (_want80g) ...[
                TextFormField(
                  controller: _pan,
                  textCapitalization: TextCapitalization.characters,
                  maxLength: 10,
                  decoration: InputDecoration(labelText: t.panNumber, counterText: ''),
                  validator: (x) => (x == null || x.trim().isEmpty) ? t.required : v.panField(x),
                ),
                const SizedBox(height: AppSpacing.sm),
                TextFormField(
                  controller: _address,
                  maxLines: 2,
                  maxLength: 300,
                  decoration: InputDecoration(labelText: t.addressFor80g),
                  validator: v.text(min: 10, max: 300),
                ),
              ],
            ],
            const SizedBox(height: AppSpacing.sm),
            // Owner decision 2026-11-01: the receipt shows the name exactly as in the donor's profile; donors can fix a
            // nickname from Google/Facebook before paying.
            Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
              decoration: BoxDecoration(
                color: _want80g ? const Color(0xFFFFF7E6) : AppColors.primarySoft,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(children: [
                const Icon(Icons.receipt_long_outlined, size: 20, color: AppColors.textMuted),
                const SizedBox(width: 8),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(t.rcpNameOnReceipt('${me?.name ?? ''}'), style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(_want80g ? t.rcpNameMatchPan : t.receiptTo('${me?.name ?? ''}', me?.email ?? ''),
                      style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                ])),
                TextButton(
                  onPressed: () async {
                    await context.push('/profile');
                    ref.invalidate(meProvider);
                  },
                  child: Text(t.rcpEditName),
                ),
              ]),
            ),
            const SizedBox(height: AppSpacing.md),
            FilledButton(
              onPressed: _busy ? null : _continue,
              child: _busy
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white))
                  : Text(t.donateAmount(formatInr(selected ?? 0, whole: true))),
            ),
            const SizedBox(height: 6),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              const Icon(Icons.lock_outline, size: 14, color: AppColors.textMuted),
              const SizedBox(width: 4),
              Flexible(child: Text(t.paysNgoDirectly, style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
            ]),
          ]),
        ),
      ),
    );
  }
}

/// DEVELOPMENT ONLY: stands in for the payment provider's own checkout screen (Razorpay etc.). No real money.
class TestPaymentPage extends ConsumerStatefulWidget {
  const TestPaymentPage({super.key, required this.checkout, required this.title, required this.ngoId, this.campaignId});
  final Map<String, dynamic> checkout;
  final String title;
  final String ngoId;
  final String? campaignId;
  @override
  ConsumerState<TestPaymentPage> createState() => _TestPaymentPageState();
}

class _TestPaymentPageState extends ConsumerState<TestPaymentPage> {
  bool _busy = false;

  Future<void> _finish(String scenario) async {
    setState(() => _busy = true);
    try {
      final r = await ref.read(apiProvider).post('/api/v1/dev/mock-payments/${widget.checkout['orderId']}/complete',
          body: {'scenario': scenario});
      if (!mounted) return;
      ref.invalidate(publicCampaignsProvider);
      if (widget.campaignId != null) ref.invalidate(publicCampaignProvider(widget.campaignId!));
      ref.invalidate(myDonationsProvider);
      await Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => DonationResultPage(
          donationId: '${r['donationId']}', status: '${r['status']}', amount: widget.checkout['amount'], title: widget.title,
          ngoId: widget.ngoId, campaignId: widget.campaignId)));
    } catch (e) {
      if (mounted) {
        showSnack(context, ApiError.from(e).message, error: true);
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final amount = formatInr(widget.checkout['amount']);
    return PopScope(
      canPop: !_busy,
      child: Scaffold(
        appBar: AppBar(title: Text(t.testPaymentTitle)),
        body: ListView(padding: const EdgeInsets.all(AppSpacing.lg), children: [
          AppCard(
            color: const Color(0xFFFFF4E5),
            child: Row(children: [
              const Icon(Icons.science_outlined, color: Color(0xFFB45309)),
              const SizedBox(width: AppSpacing.sm),
              Expanded(child: Text(t.testPaymentNote)),
            ]),
          ),
          const SizedBox(height: AppSpacing.lg),
          Text(widget.title, style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.sm),
          Text(amount, style: Theme.of(context).textTheme.displaySmall?.copyWith(fontWeight: FontWeight.w700), textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.xl),
          FilledButton.icon(
            onPressed: _busy ? null : () => _finish('SUCCESS'),
            icon: const Icon(Icons.check_circle_outline),
            label: Text(t.testPaySuccess(amount)),
          ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
            onPressed: _busy ? null : () => _finish('FAILURE'),
            icon: const Icon(Icons.error_outline),
            label: Text(t.testPayFailure),
          ),
          const SizedBox(height: AppSpacing.sm),
          TextButton(onPressed: _busy ? null : () => _finish('CANCELLATION'), child: Text(t.testPayCancel)),
          if (_busy) const Padding(padding: EdgeInsets.all(AppSpacing.md), child: Center(child: CircularProgressIndicator())),
        ]),
      ),
    );
  }
}

/// DAPP-013..016: payment outcome. PENDING can be re-checked with the provider.
class DonationResultPage extends ConsumerStatefulWidget {
  const DonationResultPage({super.key, required this.donationId, required this.status, required this.amount, required this.title,
      required this.ngoId, this.campaignId});
  final String donationId;
  final String status;
  final Object? amount;
  final String title;
  final String ngoId;
  final String? campaignId;
  @override
  ConsumerState<DonationResultPage> createState() => _DonationResultPageState();
}

class _DonationResultPageState extends ConsumerState<DonationResultPage> {
  late String _status = widget.status;

  Future<void> _refresh() async {
    try {
      final r = await ref.read(apiProvider).post('/api/v1/donations/${widget.donationId}/refresh');
      if (mounted) setState(() => _status = '${r['status']}');
    } catch (e) {
      if (mounted) showSnack(context, ApiError.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = L10n.of(context);
    final amount = formatInr(widget.amount);
    final paid = _status == 'PAID';
    final failed = _status == 'FAILED' || _status == 'CANCELLED';
    final (icon, color, title, body) = paid
        ? (Icons.volunteer_activism, AppColors.success, t.thankYouTitle, t.thankYouBody(amount, widget.title))
        : failed
            ? (Icons.error_outline, AppColors.error, t.paymentFailedTitle, t.paymentFailedBody)
            : (Icons.hourglass_top, const Color(0xFFB45309), t.paymentPendingTitle, t.paymentPendingBody);
    return Scaffold(
      appBar: AppBar(automaticallyImplyLeading: false),
      body: ListView(padding: const EdgeInsets.all(AppSpacing.lg), children: [
        const SizedBox(height: AppSpacing.xl),
        Icon(icon, size: 72, color: color),
        const SizedBox(height: AppSpacing.md),
        Text(title, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
        const SizedBox(height: AppSpacing.sm),
        Text(body, textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, height: 1.4)),
        const SizedBox(height: AppSpacing.xl),
        if (!paid && !failed)
          OutlinedButton.icon(onPressed: _refresh, icon: const Icon(Icons.refresh), label: Text(t.checkAgain)),
        if (failed)
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop();
              startDonation(context, ngoId: widget.ngoId, campaignId: widget.campaignId, title: widget.title);
            },
            child: Text(t.tryAgain),
          ),
        if (paid)
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop();
              context.go('/donor/donations');
            },
            child: Text(t.myDonations),
          ),
        const SizedBox(height: AppSpacing.sm),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(t.done)),
      ]),
    );
  }
}

final myDonationsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  ref.watch(sessionKeyProvider);
  final r = await ref.read(apiProvider).getJson('/api/v1/donations/mine');
  return (r['items'] as List? ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// DAPP-017: the donor's donations (attempts that reached the payment step).
class MyDonationsPage extends ConsumerWidget {
  const MyDonationsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final data = ref.watch(myDonationsProvider);
    final receipts = {for (final r in ref.watch(myReceiptsProvider).value ?? const <Map<String, dynamic>>[])
      if (r['status'] != 'VOIDED' && r['status'] != 'REISSUED') '${r['donationId']}': r};
    return Scaffold(
      appBar: AppBar(title: Text(t.navDonations), actions: [
        IconButton(tooltip: t.rcpMine, icon: const Icon(Icons.receipt_long_outlined), onPressed: () => context.push('/donor/receipts')),
      ]),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(myDonationsProvider);
          ref.invalidate(myReceiptsProvider);
        },
        child: data.when(
          loading: () => const LoadingView(),
          error: (e, _) => ListView(children: [ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(myDonationsProvider))]),
          data: (list) {
            if (list.isEmpty) {
              return ListView(children: [
                EmptyState(icon: Icons.volunteer_activism_outlined, title: t.noDonationsYetDonor, message: t.noDonationsYetDonorHint,
                    action: FilledButton(onPressed: () => context.go('/donor/discover'), child: Text(t.discoverTitle))),
              ]);
            }
            final paid = list.where((d) => d['status'] == 'PAID' || d['status'] == 'PARTIALLY_REFUNDED');
            final total = paid.fold<num>(0, (s, d) => s + (num.tryParse('${d['amount']}') ?? 0));
            return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
              AppCard(
                color: AppColors.primarySoft,
                child: Row(children: [
                  const Icon(Icons.favorite, color: AppColors.primary),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(child: Text(t.totalGiven(formatInr(total, whole: true), paid.length),
                      style: const TextStyle(fontWeight: FontWeight.w600))),
                ]),
              ),
              const SizedBox(height: AppSpacing.sm),
              for (final d in list)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: AppCard(
                    onTap: d['campaignId'] == null ? null : () => context.push('/donor/campaign/${d['campaignId']}'),
                    child: Row(children: [
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${d['campaignTitle'] ?? d['ngoName'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w600)),
                        if (d['campaignTitle'] != null)
                          Text('${d['ngoName'] ?? ''}', style: const TextStyle(color: AppColors.textMuted)),
                        Text(formatDate(d['paidAt'] ?? d['createdAt']), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                        if (receipts['${d['id']}'] != null)
                          TextButton.icon(
                            style: TextButton.styleFrom(padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                            onPressed: () => context.push('/donor/receipts/${receipts['${d['id']}']!['id']}'),
                            icon: const Icon(Icons.receipt_long_outlined, size: 18),
                            label: Text(t.rcpViewReceipt),
                          )
                        else if (d['status'] == 'PAID')
                          Text(t.rcpBeingPrepared, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                      ])),
                      Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                        Text(formatInr(d['amount']), style: const TextStyle(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 4),
                        StatusBadge(_donationStatus(t, '${d['status']}'),
                            color: d['status'] == 'PAID' ? AppColors.success : d['status'] == 'FAILED' ? AppColors.error : AppColors.textMuted),
                        if (d['wants80g'] == true)
                          const Padding(padding: EdgeInsets.only(top: 4), child: StatusBadge('80G', tone: BadgeTone.info)),
                      ]),
                    ]),
                  ),
                ),
            ]);
          },
        ),
      ),
    );
  }
}

String _donationStatus(L10n t, String s) => t.donationStatusLabel(s);

/// NGO admins: turn on online donations. Money goes straight to the NGO's own payment account (owner decision a).
class PaymentSettingsPage extends ConsumerWidget {
  const PaymentSettingsPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = L10n.of(context);
    final ngoId = ref.watch(currentNgoIdProvider);
    final m = ref.watch(currentMembershipProvider);
    if (ngoId == null) return const Scaffold(body: LoadingView());
    final data = ref.watch(paymentSettingsProvider(ngoId));
    return Scaffold(
      appBar: AppBar(title: Text(t.onlinePayments)),
      body: data.when(
        loading: () => const LoadingView(),
        error: (e, _) => ErrorState(message: ApiError.from(e).message, onRetry: () => ref.invalidate(paymentSettingsProvider(ngoId))),
        data: (s) {
          final status = '${s['status']}';
          final on = status == 'ACTIVE';
          return ListView(padding: const EdgeInsets.all(AppSpacing.md), children: [
            AppCard(
              color: on ? AppColors.primarySoft : null,
              child: Row(children: [
                Icon(on ? Icons.check_circle : Icons.payments_outlined, color: on ? AppColors.success : AppColors.textMuted, size: 32),
                const SizedBox(width: AppSpacing.md),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(on ? t.paymentsOn : t.paymentsOff, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
                  if (on && s['provider'] == 'MOCK') Text(t.paymentsTestMode, style: const TextStyle(color: AppColors.textMuted)),
                ])),
              ]),
            ),
            const SizedBox(height: AppSpacing.md),
            Text(t.paymentsHowItWorks, style: const TextStyle(height: 1.5)),
            const SizedBox(height: AppSpacing.lg),
            if (m?.isAdmin ?? false) ...[
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(kMinTouchTarget)),
                onPressed: () => showSnack(context, t.razorpayComingSoon),
                icon: const Icon(Icons.account_balance_outlined),
                label: Text(t.connectRazorpay),
              ),
              const SizedBox(height: AppSpacing.sm),
              if (!on)
                BusyButton(
                  label: t.turnOnTestPayments,
                  icon: Icons.science_outlined,
                  onPressed: () async {
                    try {
                      await ref.read(apiProvider).put('/api/v1/ngos/$ngoId/payment-settings', body: {
                        'provider': 'MOCK', 'merchantReference': 'mock_${ngoId.substring(0, 8)}', 'guestCheckoutAllowed': true,
                        'minAmount': '10', 'maxAmount': '1000000', 'receiptPrefix': 'RCPT',
                      });
                      ref.invalidate(paymentSettingsProvider(ngoId));
                    } catch (e) {
                      if (context.mounted) showSnack(context, ApiError.from(e).message, error: true);
                    }
                  },
                ),
            ] else
              Text(t.paymentsAdminOnly, style: const TextStyle(color: AppColors.textMuted)),
          ]);
        },
      ),
    );
  }
}

final paymentSettingsProvider = FutureProvider.family<Map<String, dynamic>, String>((ref, ngoId) {
  ref.watch(sessionKeyProvider);
  return ref.read(apiProvider).getJson('/api/v1/ngos/$ngoId/payment-settings');
});
