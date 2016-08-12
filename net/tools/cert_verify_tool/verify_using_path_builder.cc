// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "net/tools/cert_verify_tool/verify_using_path_builder.h"

#include <iostream>

#include "base/memory/ptr_util.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_util.h"
#include "crypto/sha2.h"
#include "net/base/net_errors.h"
#include "net/base/test_completion_callback.h"
#include "net/cert/internal/cert_issuer_source_aia.h"
#include "net/cert/internal/cert_issuer_source_static.h"
#include "net/cert/internal/parse_name.h"
#include "net/cert/internal/parsed_certificate.h"
#include "net/cert/internal/path_builder.h"
#include "net/cert/internal/signature_policy.h"
#include "net/cert/internal/trust_store.h"
#include "net/cert_net/cert_net_fetcher_impl.h"
#include "net/tools/cert_verify_tool/cert_verify_tool_util.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_builder.h"

#if defined(OS_LINUX)
#include "net/proxy/proxy_config.h"
#include "net/proxy/proxy_config_service_fixed.h"
#endif

namespace {

std::string GetUserAgent() {
  return "cert_verify_tool/0.1";
}

// Converts a base::Time::Exploded to a net::der::GeneralizedTime.
// TODO(mattm): This function exists in cast_cert_validator.cc also. Dedupe it?
net::der::GeneralizedTime ConvertExplodedTime(
    const base::Time::Exploded& exploded) {
  net::der::GeneralizedTime result;
  result.year = exploded.year;
  result.month = exploded.month;
  result.day = exploded.day_of_month;
  result.hours = exploded.hour;
  result.minutes = exploded.minute;
  result.seconds = exploded.second;
  return result;
}

// Dumps a chain of ParsedCertificate objects to a PEM file.
bool DumpParsedCertificateChain(
    const base::FilePath& file_path,
    const std::vector<scoped_refptr<net::ParsedCertificate>>& chain) {
  std::vector<std::string> pem_encoded_chain;
  for (const auto& cert : chain) {
    std::string der_cert;
    cert->der_cert().AsStringPiece().CopyToString(&der_cert);
    std::string pem;
    if (!net::X509Certificate::GetPEMEncodedFromDER(der_cert, &pem)) {
      std::cerr << "ERROR: GetPEMEncodedFromDER failed\n";
      return false;
    }
    pem_encoded_chain.push_back(pem);
  }
  return WriteToFile(file_path, base::JoinString(pem_encoded_chain, ""));
}

// Returns a hex-encoded sha256 of the DER-encoding of |cert|.
std::string FingerPrintParsedCertificate(const net::ParsedCertificate* cert) {
  std::string hash = crypto::SHA256HashString(cert->der_cert().AsStringPiece());
  return base::HexEncode(hash.data(), hash.size());
}

// Returns a textual representation of the Subject of |cert|.
std::string SubjectFromParsedCertificate(const net::ParsedCertificate* cert) {
  net::RDNSequence subject, issuer;
  if (!net::ParseName(cert->tbs().subject_tlv, &subject))
    return std::string();
  std::string subject_str;
  if (!net::ConvertToRFC2253(subject, &subject_str))
    return std::string();
  return subject_str;
}

}  // namespace

// Verifies |target_der_cert| using CertPathBuilder.
bool VerifyUsingPathBuilder(
    const CertInput& target_der_cert,
    const std::vector<CertInput>& intermediate_der_certs,
    const std::vector<CertInput>& root_der_certs,
    const base::Time at_time,
    const base::FilePath& dump_prefix_path) {
  std::cout << "NOTE: CertPathBuilder does not currently use OS trust settings "
               "(--roots must be specified).\n";
  std::cerr << "WARNING: --hostname is not yet verified with CertPathBuilder\n";

  base::Time::Exploded exploded_time;
  at_time.UTCExplode(&exploded_time);
  net::der::GeneralizedTime time = ConvertExplodedTime(exploded_time);

  net::TrustStore trust_store;
  for (const auto& der_cert : root_der_certs) {
    scoped_refptr<net::ParsedCertificate> cert =
        net::ParsedCertificate::CreateFromCertificateCopy(der_cert.der_cert,
                                                          {});
    if (!cert)
      PrintCertError("ERROR: ParsedCertificate failed:", der_cert);
    else
      trust_store.AddTrustedCertificate(cert);
  }

  net::CertIssuerSourceStatic intermediate_cert_issuer_source;
  for (const auto& der_cert : intermediate_der_certs) {
    scoped_refptr<net::ParsedCertificate> cert =
        net::ParsedCertificate::CreateFromCertificateCopy(der_cert.der_cert,
                                                          {});
    if (!cert)
      PrintCertError("ERROR: ParsedCertificate failed:", der_cert);
    else
      intermediate_cert_issuer_source.AddCert(cert);
  }

  scoped_refptr<net::ParsedCertificate> target_cert =
      net::ParsedCertificate::CreateFromCertificateCopy(
          target_der_cert.der_cert, {});
  if (!target_cert) {
    PrintCertError("ERROR: ParsedCertificate failed:", target_der_cert);
    return false;
  }

  // Verify the chain.
  net::SimpleSignaturePolicy signature_policy(2048);
  net::CertPathBuilder::Result result;
  net::CertPathBuilder path_builder(target_cert, &trust_store,
                                    &signature_policy, time, &result);
  path_builder.AddCertIssuerSource(&intermediate_cert_issuer_source);

  // TODO(mattm): add command line flags to configure using CertIssuerSourceAia
  // (similar to VERIFY_CERT_IO_ENABLED flag for CertVerifyProc).
  net::URLRequestContextBuilder url_request_context_builder;
  url_request_context_builder.set_user_agent(GetUserAgent());
#if defined(OS_LINUX)
  // On Linux, use a fixed ProxyConfigService, since the default one
  // depends on glib.
  //
  // TODO(akalin): Remove this once http://crbug.com/146421 is fixed.
  url_request_context_builder.set_proxy_config_service(
      base::WrapUnique(new net::ProxyConfigServiceFixed(net::ProxyConfig())));
#endif
  std::unique_ptr<net::URLRequestContext> url_request_context =
      url_request_context_builder.Build();
  net::CertNetFetcherImpl cert_net_fetcher(url_request_context.get());
  net::CertIssuerSourceAia aia_cert_issuer_source(&cert_net_fetcher);
  path_builder.AddCertIssuerSource(&aia_cert_issuer_source);

  net::TestClosure callback;
  net::CompletionStatus rv = path_builder.Run(callback.closure());

  if (rv == net::CompletionStatus::ASYNC) {
    DVLOG(1) << "waiting for async completion...";
    callback.WaitForResult();
    DVLOG(1) << "async completed.";
  }

  std::cout << "CertPathBuilder best result: "
            << net::ErrorToShortString(result.error()) << "\n";

  for (size_t i = 0; i < result.paths.size(); ++i) {
    std::cout << "path " << i << " "
              << net::ErrorToShortString(result.paths[i]->error)
              << ((result.best_result_index == i) ? " (best)" : "") << "\n";
    for (const auto& cert : result.paths[i]->path) {
      std::cout << " " << FingerPrintParsedCertificate(cert.get()) << " "
                << SubjectFromParsedCertificate(cert.get()) << "\n";
    }
  }

  // TODO(mattm): add flag to dump all paths, not just the final one?
  if (!dump_prefix_path.empty() && result.paths.size()) {
    if (!DumpParsedCertificateChain(
            dump_prefix_path.AddExtension(
                FILE_PATH_LITERAL(".CertPathBuilder.pem")),
            result.paths[result.best_result_index]->path)) {
      return false;
    }
  }

  return result.error() == net::OK;
}
