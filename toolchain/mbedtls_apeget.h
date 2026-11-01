/* mbed TLS 3.6.x configuration for ape-get (crypto module):
   TLS 1.2 client only — RSA/ECDHE key exchange, AES-GCM,
   SHA-1/256/384, x509 chain verification. Everything else (servers,
   TLS 1.3, PSA, sessions, threading, fs I/O) is compiled out.
   DHE-RSA is deliberately absent: every live release host
   offers ECDHE, which also carries the forward-secrecy property. */
#ifndef APEGET_MBEDTLS_CONFIG_H
#define APEGET_MBEDTLS_CONFIG_H

#define MBEDTLS_AES_C
#define MBEDTLS_ASN1_PARSE_C
#define MBEDTLS_ASN1_WRITE_C
#define MBEDTLS_BASE64_C
#define MBEDTLS_BIGNUM_C
#define MBEDTLS_CIPHER_C
#define MBEDTLS_CTR_DRBG_C
#define MBEDTLS_ECDH_C
#define MBEDTLS_ECDSA_C
#define MBEDTLS_ECP_C
#define MBEDTLS_ENTROPY_C
#define MBEDTLS_ERROR_C
#define MBEDTLS_GCM_C
#define MBEDTLS_HMAC_DRBG_C
#define MBEDTLS_MD_C
#define MBEDTLS_NET_C
#define MBEDTLS_OID_C
#define MBEDTLS_PEM_PARSE_C
#define MBEDTLS_PK_C
#define MBEDTLS_PK_PARSE_C
#define MBEDTLS_PKCS1_V15
#define MBEDTLS_PKCS1_V21
#define MBEDTLS_PLATFORM_C
#define MBEDTLS_RSA_C
#define MBEDTLS_SHA1_C
#define MBEDTLS_SHA256_C
/* 3.6 splits SHA-384 into its own module: without it every SHA-384
   signature (half the Mozilla roots, GitHub/Codeberg/GitLab chains)
   parses as UNKNOWN_SIG_ALG */
#define MBEDTLS_SHA384_C
#define MBEDTLS_SHA512_C
#define MBEDTLS_SSL_CLI_C
/* mandatory for virtual-hosted hosts: without SNI gitlab's load
   balancer aborts with handshake_failure(40) before ServerHello */
#define MBEDTLS_SSL_SERVER_NAME_INDICATION
#define MBEDTLS_SSL_PROTO_TLS1_2
#define MBEDTLS_SSL_TLS_C
#define MBEDTLS_TIMING_C
#define MBEDTLS_X509_USE_C
#define MBEDTLS_X509_CRT_PARSE_C

#define MBEDTLS_ECP_DP_SECP256R1_ENABLED
#define MBEDTLS_ECP_DP_SECP384R1_ENABLED
#define MBEDTLS_ECP_DP_SECP521R1_ENABLED
#define MBEDTLS_ECP_DP_CURVE25519_ENABLED

#define MBEDTLS_KEY_EXCHANGE_RSA_ENABLED
#define MBEDTLS_KEY_EXCHANGE_ECDHE_RSA_ENABLED
#define MBEDTLS_KEY_EXCHANGE_ECDHE_ECDSA_ENABLED

#define MBEDTLS_SSL_MAX_CONTENT_LEN 8192
#define MBEDTLS_MPI_MAX_SIZE 4096
#define MBEDTLS_ECP_WINDOW_SIZE 2

#endif
