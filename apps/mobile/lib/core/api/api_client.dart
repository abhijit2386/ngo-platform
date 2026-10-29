import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/auth_service.dart';
import '../config/app_config.dart';
import 'api_error.dart';

String newCorrelationId() => _uuidV4();

/// Random UUIDv4 for idempotency keys / correlation ids.
String _uuidV4() {
  final r = Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  String h(int i) => b[i].toRadixString(16).padLeft(2, '0');
  final s = List.generate(16, h).join();
  return '${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}';
}

String newIdempotencyKey() => _uuidV4();

final apiProvider = Provider<Api>((ref) => Api(ref.watch(authServiceProvider)));

/// Thin typed wrapper over Dio. All errors surface as [ApiError].
class Api {
  Api(this._auth) {
    _dio = Dio(BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 30),
      headers: {'Accept': 'application/json'},
    ));
    _dio.interceptors.add(QueuedInterceptorsWrapper(
      onRequest: (options, handler) async {
        options.headers['X-Correlation-Id'] = newCorrelationId();
        final token = await _auth.validAccessToken();
        if (token != null) options.headers['Authorization'] = 'Bearer $token';
        handler.next(options);
      },
      onError: (e, handler) async {
        // One transparent refresh+retry on 401 (token expired between check and use).
        if (e.response?.statusCode == 401 && e.requestOptions.extra['retried'] != true) {
          final refreshed = await _auth.refresh();
          if (refreshed != null) {
            final opts = e.requestOptions..extra['retried'] = true;
            opts.headers['Authorization'] = 'Bearer $refreshed';
            try {
              return handler.resolve(await _dio.fetch(opts));
            } on DioException catch (err) {
              return handler.next(err);
            }
          }
          _auth.markSessionExpired();
        }
        handler.next(e);
      },
    ));
  }

  final AuthService _auth;
  late final Dio _dio;

  Dio get dio => _dio;

  Future<T> _wrap<T>(Future<Response<dynamic>> Function() call, T Function(dynamic) map) async {
    try {
      final r = await call();
      return map(r.data);
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  Future<Map<String, dynamic>> getJson(String path, {Map<String, dynamic>? query}) =>
      _wrap(() => _dio.get(path, queryParameters: _clean(query)), (d) => Map<String, dynamic>.from(d as Map));

  Future<List<dynamic>> getList(String path, {Map<String, dynamic>? query}) =>
      _wrap(() => _dio.get(path, queryParameters: _clean(query)), (d) => List<dynamic>.from(d as List));

  Future<Map<String, dynamic>> post(String path, {Object? body, String? idempotencyKey}) => _wrap(
      () => _dio.post(path, data: body ?? const {},
          options: Options(headers: {if (idempotencyKey != null) 'Idempotency-Key': idempotencyKey})),
      (d) => d is Map ? Map<String, dynamic>.from(d) : <String, dynamic>{});

  Future<Map<String, dynamic>> put(String path, {Object? body}) =>
      _wrap(() => _dio.put(path, data: body ?? const {}), (d) => d is Map ? Map<String, dynamic>.from(d) : <String, dynamic>{});

  Future<void> delete(String path) => _wrap(() => _dio.delete(path), (_) {});

  Future<List<int>> bytes(String path, {Map<String, dynamic>? query}) => _wrap(
      () => _dio.get<List<int>>(path, queryParameters: _clean(query), options: Options(responseType: ResponseType.bytes)),
      (d) => List<int>.from(d as List));

  Future<Map<String, dynamic>> upload(String path, {required List<int> bytes, required String filename, Map<String, dynamic>? fields}) {
    final form = FormData.fromMap({...?fields, 'file': MultipartFile.fromBytes(bytes, filename: filename)});
    return _wrap(() => _dio.post(path, data: form), (d) => Map<String, dynamic>.from(d as Map));
  }

  static Map<String, dynamic>? _clean(Map<String, dynamic>? q) =>
      q == null ? null : (Map.of(q)..removeWhere((_, v) => v == null || (v is String && v.isEmpty)));
}
