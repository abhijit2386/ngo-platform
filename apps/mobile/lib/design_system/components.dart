import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'tokens.dart';
import '../localization/app_localizations.dart';

final _inr = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 2);
final _inrWhole = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 0);

/// Formats an amount (string/num from the API) as Indian rupees. Amounts are strings on the wire to avoid floats.
String formatInr(Object? value, {bool whole = false}) {
  final n = value is num ? value : num.tryParse('${value ?? 0}') ?? 0;
  return (whole && n == n.roundToDouble() ? _inrWhole : _inr).format(n);
}

String formatDate(Object? iso) {
  if (iso == null) return '—';
  final d = DateTime.tryParse('$iso');
  return d == null ? '$iso' : DateFormat('d MMM yyyy').format(d.toLocal());
}

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.onTap, this.padding = const EdgeInsets.all(AppSpacing.md), this.color});
  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: color,
      clipBehavior: Clip.antiAlias,
      child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
    );
  }
}

enum BadgeTone { success, warning, error, info, neutral }

class StatusBadge extends StatelessWidget {
  const StatusBadge(this.label, {super.key, this.tone = BadgeTone.neutral, this.color});
  final String label;
  final BadgeTone tone;
  final Color? color;

  static BadgeTone toneFor(String status) => switch (status) {
        'ACTIVE' || 'APPROVED' || 'PAID' || 'ELIGIBLE' || 'PUBLISHED' || 'GENERATED' || 'DELIVERED' || 'RESOLVED' || 'CLOSED' =>
          BadgeTone.success,
        'TRIAL' || 'PENDING' || 'GRACE' || 'INFO_REQUESTED' || 'PENDING_ACCEPTANCE' || 'OPEN' || 'IN_PROGRESS' || 'DRAFT' =>
          BadgeTone.warning,
        'SUSPENDED' || 'REJECTED' || 'FAILED' || 'EXPIRED' || 'CANCELLED' || 'ESCALATED' || 'GENERATION_FAILED' => BadgeTone.error,
        _ => BadgeTone.info,
      };

  @override
  Widget build(BuildContext context) {
    final c = color ??
        switch (tone) {
          BadgeTone.success => AppColors.success,
          BadgeTone.warning => const Color(0xFFB45309),
          BadgeTone.error => AppColors.error,
          BadgeTone.info => AppColors.growth,
          BadgeTone.neutral => AppColors.textMuted,
        };
    // Backend codes (TRIAL, INFO_REQUESTED, ...) are shown in the user's language; free text passes through.
    final text = RegExp(r'^[A-Z][A-Z0-9_]*$').hasMatch(label) ? L10n.of(context).statusLabel(label) : label;
    return Semantics(
      label: text,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(AppRadius.chip)),
        child: Text(text, style: TextStyle(color: c, fontWeight: FontWeight.w600, fontSize: 12)),
      ),
    );
  }
}

/// Primary action that shows progress and ignores taps while busy (prevents duplicate submissions, spec §21.2).
class BusyButton extends StatefulWidget {
  const BusyButton({super.key, required this.label, required this.onPressed, this.outlined = false, this.icon});
  final String label;
  final Future<void> Function()? onPressed;
  final bool outlined;
  final IconData? icon;

  @override
  State<BusyButton> createState() => _BusyButtonState();
}

class _BusyButtonState extends State<BusyButton> {
  bool _busy = false;

  Future<void> _run() async {
    if (_busy || widget.onPressed == null) return;
    setState(() => _busy = true);
    try {
      await widget.onPressed!();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final child = _busy
        ? const SizedBox(height: 22, width: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
        : Row(mainAxisSize: MainAxisSize.min, children: [
            if (widget.icon != null) ...[Icon(widget.icon, size: 20), const SizedBox(width: 8)],
            Flexible(child: Text(widget.label, overflow: TextOverflow.ellipsis)),
          ]);
    final onPressed = widget.onPressed == null || _busy ? null : _run;
    return widget.outlined
        ? OutlinedButton(onPressed: onPressed, child: child)
        : FilledButton(onPressed: onPressed, child: child);
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.title, this.message, this.action});
  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          CircleAvatar(radius: 36, backgroundColor: AppColors.primarySoft, child: Icon(icon, size: 36, color: AppColors.primary)),
          const SizedBox(height: AppSpacing.md),
          Text(title, style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
          if (message != null) ...[
            const SizedBox(height: AppSpacing.sm),
            Text(message!, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.textMuted)),
          ],
          if (action != null) ...[const SizedBox(height: AppSpacing.lg), action!],
        ]),
      ),
    );
  }
}

class ErrorState extends StatelessWidget {
  const ErrorState({super.key, required this.message, this.onRetry, this.retryLabel = 'Retry'});
  final String message;
  final VoidCallback? onRetry;
  final String retryLabel;

  @override
  Widget build(BuildContext context) {
    return EmptyState(
      icon: Icons.cloud_off_outlined,
      title: message,
      action: onRetry == null ? null : OutlinedButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: Text(retryLabel)),
    );
  }
}

class LoadingView extends StatelessWidget {
  const LoadingView({super.key});
  @override
  Widget build(BuildContext context) => const Center(child: CircularProgressIndicator());
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.action});
  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(0, AppSpacing.lg, 0, AppSpacing.sm),
      child: Row(children: [
        Expanded(child: Text(title, style: Theme.of(context).textTheme.titleMedium)),
        if (action != null) action!,
      ]),
    );
  }
}

class StatTile extends StatelessWidget {
  const StatTile({super.key, required this.label, required this.value, required this.icon, this.color = AppColors.primary, this.onTap});
  final String label;
  final String value;
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return AppCard(
      onTap: onTap,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, color: color),
        const SizedBox(height: AppSpacing.sm),
        Text(value, style: Theme.of(context).textTheme.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis),
        Text(label, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
      ]),
    );
  }
}

/// Quick-action tile: icon + action label; disabled tiles explain why (e.g. "Available after verification").
class ActionTile extends StatelessWidget {
  const ActionTile({super.key, required this.label, required this.icon, this.color = AppColors.primary, this.onTap, this.disabledHint});
  final String label;
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;
  final String? disabledHint;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Semantics(
      button: true,
      enabled: enabled,
      label: enabled ? label : '$label. ${disabledHint ?? ''}',
      excludeSemantics: true,
      child: Opacity(
        opacity: enabled ? 1 : 0.55,
        child: AppCard(
          onTap: onTap,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(height: AppSpacing.sm),
            Text(label, style: const TextStyle(fontWeight: FontWeight.w600), maxLines: 2, overflow: TextOverflow.ellipsis),
            if (!enabled && disabledHint != null)
              Text(disabledHint!, style: const TextStyle(color: AppColors.textMuted, fontSize: 12), maxLines: 2, overflow: TextOverflow.ellipsis),
          ]),
        ),
      ),
    );
  }
}

/// Form labels carry an "(optional)" hint; read-only summaries drop it.
final _optionalSuffix = RegExp(r'\s*\((optional|वैकल्पिक|ऐच्छिक)\)$', caseSensitive: false);

class KeyValueRow extends StatelessWidget {
  const KeyValueRow(this.label, this.value, {super.key});
  final String label;
  final String? value;
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(flex: 2, child: Text(label.replaceFirst(_optionalSuffix, ''), style: const TextStyle(color: AppColors.textMuted))),
        Expanded(flex: 3, child: Text(value == null || value!.isEmpty ? '—' : value!, textAlign: TextAlign.end,
            style: const TextStyle(fontWeight: FontWeight.w600))),
      ]),
    );
  }
}

void showSnack(BuildContext context, String message, {bool error = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      content: Text(message),
      backgroundColor: error ? AppColors.error : AppColors.primaryDark,
      behavior: SnackBarBehavior.floating,
    ));
}

Future<bool> confirm(BuildContext context, {required String title, required String message, String confirmLabel = 'Confirm', String cancelLabel = 'Cancel', bool destructive = false}) async {
  final result = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: Text(cancelLabel)),
        FilledButton(
          style: destructive ? FilledButton.styleFrom(backgroundColor: AppColors.error, minimumSize: const Size(88, 44)) : FilledButton.styleFrom(minimumSize: const Size(88, 44)),
          onPressed: () => Navigator.pop(c, true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return result ?? false;
}
