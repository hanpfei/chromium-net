// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef NET_CERT_INTERNAL_PATH_BUILDER_H_
#define NET_CERT_INTERNAL_PATH_BUILDER_H_

#include <memory>
#include <string>
#include <vector>

#include "base/callback.h"
#include "net/base/completion_callback.h"
#include "net/base/net_errors.h"
#include "net/base/net_export.h"
#include "net/cert/internal/completion_status.h"
#include "net/cert/internal/parsed_certificate.h"
#include "net/der/input.h"
#include "net/der/parse_values.h"

namespace net {

namespace der {
struct GeneralizedTime;
}

class CertPathIter;
class CertIssuerSource;
class TrustStore;
class SignaturePolicy;

// Checks whether a certificate is trusted by building candidate paths to trust
// anchors and verifying those paths according to RFC 5280. Each instance of
// CertPathBuilder is used for a single verification.
//
// WARNING: This implementation is currently experimental.  Consult an OWNER
// before using it.
class NET_EXPORT CertPathBuilder {
 public:
  // Represents a single candidate path that was built.
  struct NET_EXPORT ResultPath {
    ResultPath();
    ~ResultPath();

    // Returns true if this path was successfully verified.
    bool is_success() const { return error == OK; }

    // The candidate path, in forward direction.
    //   * path[0] is the target certificate.
    //   * path[i+1] is a candidate issuer of path[i]. The subject matches
    //   path[i]'s issuer, but nothing else is guaranteed unless is_success() is
    //   true.
    //   * path[N-1] will be a trust anchor if is_success() is true, otherwise
    //   it may or may not be a trust anchor.
    ParsedCertificateList path;

    // A net error code result of attempting to verify this path.
    // TODO(mattm): may want to have an independent result enum, which caller
    // can map to a net error if they want.
    int error = ERR_UNEXPECTED;
  };

  // Provides the overall result of path building. This includes the paths that
  // were attempted.
  struct NET_EXPORT Result {
    Result();
    ~Result();

    // Returns true if there was a valid path.
    bool is_success() const { return error() == OK; }

    // Returns the net error code of the overall best result.
    int error() const {
      if (paths.empty())
        return ERR_CERT_AUTHORITY_INVALID;
      return paths[best_result_index]->error;
    }

    // List of paths that were attempted and the result for each.
    std::vector<std::unique_ptr<ResultPath>> paths;

    // Index into |paths|. Before use, |paths.empty()| must be checked.
    // NOTE: currently the definition of "best" is fairly limited. Successful is
    // better than unsuccessful, but otherwise nothing is guaranteed.
    size_t best_result_index = 0;

   private:
    DISALLOW_COPY_AND_ASSIGN(Result);
  };

  // TODO(mattm): allow caller specified hook/callback to extend path
  // verification.
  //
  // Creates a CertPathBuilder that attempts to find a path from |cert| to a
  // trust anchor in |trust_store|, which satisfies |signature_policy| and is
  // valid at |time|.  Details of attempted path(s) are stored in |*result|.
  //
  // The caller must keep |trust_store|, |signature_policy|, and |*result| valid
  // for the lifetime of the CertPathBuilder.
  CertPathBuilder(scoped_refptr<ParsedCertificate> cert,
                  const TrustStore* trust_store,
                  const SignaturePolicy* signature_policy,
                  const der::GeneralizedTime& time,
                  Result* result);
  ~CertPathBuilder();

  // Adds a CertIssuerSource to provide intermediates for use in path building.
  // Multiple sources may be added. Must not be called after Run is called.
  // The |*cert_issuer_source| must remain valid for the lifetime of the
  // CertPathBuilder.
  //
  // (If no issuer sources are added, the target certificate will only verify if
  // it is a trust anchor or is directly signed by a trust anchor.)
  void AddCertIssuerSource(CertIssuerSource* cert_issuer_source);

  // Begins verification of the target certificate.
  //
  // If the return value is SYNC then the verification is complete and the
  // |result| value can be inspected for the status, and |callback| will not be
  // called.
  // If the return value is ASYNC, the |callback| will be called asynchronously
  // once the verification is complete. |result| should not be examined or
  // modified until the |callback| is run.
  //
  // If |callback| is null, verification always completes synchronously, even if
  // it fails to find a valid path and one could have been found asynchronously.
  //
  // The CertPathBuilder may be deleted while an ASYNC verification is pending,
  // in which case the verification is cancelled, |callback| will not be called,
  // and the output Result will be in an undefined state.
  // It is safe to delete the CertPathBuilder during the |callback|.
  // Run must not be called more than once on each CertPathBuilder instance.
  CompletionStatus Run(const base::Closure& callback);

 private:
  enum State {
    STATE_NONE,
    STATE_GET_NEXT_PATH,
    STATE_GET_NEXT_PATH_COMPLETE,
  };

  CompletionStatus DoLoop(bool allow_async);

  CompletionStatus DoGetNextPath(bool allow_async);
  void HandleGotNextPath();
  CompletionStatus DoGetNextPathComplete();

  void AddResultPath(const ParsedCertificateList& path, bool is_success);

  base::Closure callback_;

  std::unique_ptr<CertPathIter> cert_path_iter_;
  const TrustStore* trust_store_;
  const SignaturePolicy* signature_policy_;
  const der::GeneralizedTime time_;

  // Stores the next complete path to attempt verification on. This is filled in
  // by |cert_path_iter_| during the STATE_GET_NEXT_PATH step, and thus should
  // only be accessed during the STATE_GET_NEXT_PATH_COMPLETE step.
  // (Will be empty if all paths have been tried, otherwise will be a candidate
  // path starting with the target cert and ending with a trust anchor.)
  ParsedCertificateList next_path_;
  State next_state_;

  Result* out_result_;

  DISALLOW_COPY_AND_ASSIGN(CertPathBuilder);
};

}  // namespace net

#endif  // NET_CERT_INTERNAL_PATH_BUILDER_H_
