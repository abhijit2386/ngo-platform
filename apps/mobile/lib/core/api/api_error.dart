import 'package:dio/dio.dart';

/// Mirrors the backend error body (spec §21.3).
class ApiError implements Exception {
  ApiError({required this.code, required this.message, this.status, this.correlationId, this.fieldErrors = const {}});

  final String code;
  final String message;
  final int? status;
  final String? correlationId;
  final Map<String, String> fieldErrors;

  bool get isNetwork => code == 'NETWORK';
  bool get isUnauthorized => status == 401;
  bool get isForbidden => status == 403;
  bool get needsStepUp => code == 'STEP_UP_REQUIRED';
  bool get isEntitlement => status == 402;

  factory ApiError.from(Object error) {
    if (error is ApiError) return error;
    if (error is DioException) {
      final r = error.response;
      if (r == null) {
        return ApiError(code: 'NETWORK', message: 'Could not reach the server. Check your connection and retry.');
      }
      final data = r.data;
      if (data is Map) {
        final fields = <String, String>{};
        for (final f in (data['fieldErrors'] as List? ?? const [])) {
          if (f is Map) fields['${f['field']}'] = '${f['message']}';
        }
        return ApiError(
          code: '${data['code'] ?? 'HTTP_${r.statusCode}'}',
          message: '${data['message'] ?? 'Request failed'}',
          status: r.statusCode,
          correlationId: data['correlationId']?.toString(),
          fieldErrors: fields,
        );
      }
      return ApiError(code: 'HTTP_${r.statusCode}', message: _defaultMessage(r.statusCode), status: r.statusCode);
    }
    return ApiError(code: 'UNEXPECTED', message: 'Something went wrong. Please retry.');
  }

  static String _defaultMessage(int? status) => switch (status) {
        401 => 'Your session has expired. Please sign in again.',
        403 => 'You do not have permission to do this.',
        404 => 'Not found.',
        429 => 'Too many requests. Please wait a moment.',
        503 => 'Service temporarily unavailable. Please retry.',
        _ => 'Request failed. Please retry.',
      };

  @override
  String toString() => message;
}
