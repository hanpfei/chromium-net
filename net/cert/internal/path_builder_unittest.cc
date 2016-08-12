// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "net/cert/internal/path_builder.h"

#include "base/base_paths.h"
#include "base/cancelable_callback.h"
#include "base/files/file_util.h"
#include "base/location.h"
#include "base/path_service.h"
#include "base/threading/thread_task_runner_handle.h"
#include "net/base/net_errors.h"
#include "net/base/test_completion_callback.h"
#include "net/cert/internal/cert_issuer_source_static.h"
#include "net/cert/internal/parsed_certificate.h"
#include "net/cert/internal/signature_policy.h"
#include "net/cert/internal/test_helpers.h"
#include "net/cert/internal/trust_store.h"
#include "net/cert/internal/verify_certificate_chain.h"
#include "net/cert/pem_tokenizer.h"
#include "net/der/input.h"
#include "net/test/cert_test_util.h"
#include "net/test/test_certificate_data.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace net {

namespace {

using ::testing::_;
using ::testing::Invoke;
using ::testing::SaveArg;
using ::testing::StrictMock;
using ::testing::SetArgPointee;
using ::testing::Return;

// AsyncCertIssuerSourceStatic always returns its certs asynchronously.
class AsyncCertIssuerSourceStatic : public CertIssuerSource {
 public:
  class StaticAsyncRequest : public Request {
   public:
    StaticAsyncRequest(const IssuerCallback& issuers_callback,
                       ParsedCertificateList&& issuers)
        : cancelable_closure_(base::Bind(&StaticAsyncRequest::RunCallback,
                                         base::Unretained(this))),
          issuers_callback_(issuers_callback) {
      issuers_.swap(issuers);
      issuers_iter_ = issuers_.begin();
    }
    ~StaticAsyncRequest() override {}

    CompletionStatus GetNext(
        scoped_refptr<ParsedCertificate>* out_cert) override {
      if (issuers_iter_ == issuers_.end())
        *out_cert = nullptr;
      else
        *out_cert = std::move(*issuers_iter_++);
      return CompletionStatus::SYNC;
    }

    base::Closure callback() { return cancelable_closure_.callback(); }

   private:
    void RunCallback() { issuers_callback_.Run(this); }

    base::CancelableClosure cancelable_closure_;
    IssuerCallback issuers_callback_;
    ParsedCertificateList issuers_;
    ParsedCertificateList::iterator issuers_iter_;

    DISALLOW_COPY_AND_ASSIGN(StaticAsyncRequest);
  };

  ~AsyncCertIssuerSourceStatic() override {}

  void AddCert(scoped_refptr<ParsedCertificate> cert) {
    static_cert_issuer_source_.AddCert(std::move(cert));
  }

  void SyncGetIssuersOf(const ParsedCertificate* cert,
                        ParsedCertificateList* issuers) override {}
  void AsyncGetIssuersOf(const ParsedCertificate* cert,
                         const IssuerCallback& issuers_callback,
                         std::unique_ptr<Request>* out_req) override {
    num_async_gets_++;
    ParsedCertificateList issuers;
    static_cert_issuer_source_.SyncGetIssuersOf(cert, &issuers);
    std::unique_ptr<StaticAsyncRequest> req(
        new StaticAsyncRequest(issuers_callback, std::move(issuers)));
    base::ThreadTaskRunnerHandle::Get()->PostTask(FROM_HERE, req->callback());
    *out_req = std::move(req);
  }
  int num_async_gets() const { return num_async_gets_; }

 private:
  CertIssuerSourceStatic static_cert_issuer_source_;

  int num_async_gets_ = 0;
};

// Reads a data file from the unit-test data.
std::string ReadTestFileToString(const std::string& file_name) {
  // Compute the full path, relative to the src/ directory.
  base::FilePath src_root;
  PathService::Get(base::DIR_SOURCE_ROOT, &src_root);
  base::FilePath filepath = src_root.AppendASCII(file_name);

  // Read the full contents of the file.
  std::string file_data;
  if (!base::ReadFileToString(filepath, &file_data)) {
    ADD_FAILURE() << "Couldn't read file: " << filepath.value();
    return std::string();
  }

  return file_data;
}

// Reads a verify_certificate_chain_unittest-style test case from |file_name|.
// Test cases are comprised of a certificate chain, trust store, a timestamp to
// validate at, and the expected result of verification (though the expected
// result is ignored here).
void ReadVerifyCertChainTestFromFile(const std::string& file_name,
                                     std::vector<std::string>* chain,
                                     scoped_refptr<ParsedCertificate>* root,
                                     der::GeneralizedTime* time) {
  chain->clear();

  std::string file_data = ReadTestFileToString(file_name);

  std::vector<std::string> pem_headers;

  const char kCertificateHeader[] = "CERTIFICATE";
  const char kTrustedCertificateHeader[] = "TRUSTED_CERTIFICATE";
  const char kTimeHeader[] = "TIME";

  pem_headers.push_back(kCertificateHeader);
  pem_headers.push_back(kTrustedCertificateHeader);
  pem_headers.push_back(kTimeHeader);

  bool has_time = false;

  PEMTokenizer pem_tokenizer(file_data, pem_headers);
  while (pem_tokenizer.GetNext()) {
    const std::string& block_type = pem_tokenizer.block_type();
    const std::string& block_data = pem_tokenizer.data();

    if (block_type == kCertificateHeader) {
      chain->push_back(block_data);
    } else if (block_type == kTrustedCertificateHeader) {
      *root = ParsedCertificate::CreateFromCertificateCopy(block_data, {});
      ASSERT_TRUE(*root);
    } else if (block_type == kTimeHeader) {
      ASSERT_FALSE(has_time) << "Duplicate " << kTimeHeader;
      has_time = true;
      ASSERT_TRUE(der::ParseUTCTime(der::Input(&block_data), time));
    }
  }

  ASSERT_TRUE(has_time);
}

::testing::AssertionResult ReadTestPem(const std::string& file_name,
                                       const std::string& block_name,
                                       std::string* result) {
  const PemBlockMapping mappings[] = {
      {block_name.c_str(), result},
  };

  return ReadTestDataFromPemFile(file_name, mappings);
}

::testing::AssertionResult ReadTestCert(
    const std::string& file_name,
    scoped_refptr<ParsedCertificate>* result) {
  std::string der;
  ::testing::AssertionResult r = ReadTestPem(
      "net/data/ssl/certificates/" + file_name, "CERTIFICATE", &der);
  if (!r)
    return r;
  *result = ParsedCertificate::CreateFromCertificateCopy(der, {});
  if (!*result)
    return ::testing::AssertionFailure() << "CreateFromCertificateCopy failed";
  return ::testing::AssertionSuccess();
}

// Run the path builder, and wait for async completion if necessary. The return
// value signifies whether the path builder completed synchronously or
// asynchronously, not that RunPathBuilder itself is asynchronous.
CompletionStatus RunPathBuilder(CertPathBuilder* path_builder) {
  TestClosure callback;
  CompletionStatus rv = path_builder->Run(callback.closure());

  if (rv == CompletionStatus::ASYNC) {
    DVLOG(1) << "waiting for async completion...";
    callback.WaitForResult();
    DVLOG(1) << "async completed.";
  }
  return rv;
}

class PathBuilderMultiRootTest : public ::testing::Test {
 public:
  PathBuilderMultiRootTest() : signature_policy_(1024) {}

  void SetUp() override {
    ASSERT_TRUE(ReadTestCert("multi-root-A-by-B.pem", &a_by_b_));
    ASSERT_TRUE(ReadTestCert("multi-root-B-by-C.pem", &b_by_c_));
    ASSERT_TRUE(ReadTestCert("multi-root-B-by-F.pem", &b_by_f_));
    ASSERT_TRUE(ReadTestCert("multi-root-C-by-D.pem", &c_by_d_));
    ASSERT_TRUE(ReadTestCert("multi-root-C-by-E.pem", &c_by_e_));
    ASSERT_TRUE(ReadTestCert("multi-root-D-by-D.pem", &d_by_d_));
    ASSERT_TRUE(ReadTestCert("multi-root-E-by-E.pem", &e_by_e_));
    ASSERT_TRUE(ReadTestCert("multi-root-F-by-E.pem", &f_by_e_));
  }

 protected:
  scoped_refptr<ParsedCertificate> a_by_b_, b_by_c_, b_by_f_, c_by_d_, c_by_e_,
      d_by_d_, e_by_e_, f_by_e_;

  SimpleSignaturePolicy signature_policy_;
  der::GeneralizedTime time_ = {2016, 4, 11, 0, 0, 0};
};

// If the target cert is a trust anchor, it should verify and should not include
// anything else in the path.
TEST_F(PathBuilderMultiRootTest, TargetIsTrustAnchor) {
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(a_by_b_);
  trust_store.AddTrustedCertificate(b_by_f_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  EXPECT_EQ(1U, result.paths[result.best_result_index]->path.size());
  EXPECT_EQ(a_by_b_, result.paths[result.best_result_index]->path[0]);
}

// If the target cert is directly issued by a trust anchor, it should verify
// without any intermediate certs being provided.
TEST_F(PathBuilderMultiRootTest, TargetDirectlySignedByTrustAnchor) {
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(b_by_f_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  EXPECT_EQ(2U, result.paths[result.best_result_index]->path.size());
  EXPECT_EQ(a_by_b_, result.paths[result.best_result_index]->path[0]);
  EXPECT_EQ(b_by_f_, result.paths[result.best_result_index]->path[1]);
}

// Test that async cert queries are not made if the path can be successfully
// built with synchronously available certs.
TEST_F(PathBuilderMultiRootTest, TriesSyncFirst) {
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(e_by_e_);

  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(b_by_f_);
  sync_certs.AddCert(f_by_e_);

  AsyncCertIssuerSourceStatic async_certs;
  async_certs.AddCert(b_by_c_);
  async_certs.AddCert(c_by_e_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&async_certs);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  EXPECT_EQ(0, async_certs.num_async_gets());
}

// Test that async cert queries are not made if no callback is provided.
TEST_F(PathBuilderMultiRootTest, SychronousOnlyMode) {
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(e_by_e_);

  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(f_by_e_);

  AsyncCertIssuerSourceStatic async_certs;
  async_certs.AddCert(b_by_f_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&async_certs);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, path_builder.Run(base::Closure()));

  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.error());
  EXPECT_EQ(0, async_certs.num_async_gets());
}

// If async queries are needed, all async sources will be queried
// simultaneously.
TEST_F(PathBuilderMultiRootTest, TestAsyncSimultaneous) {
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(e_by_e_);

  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(b_by_c_);
  sync_certs.AddCert(b_by_f_);

  AsyncCertIssuerSourceStatic async_certs1;
  async_certs1.AddCert(c_by_e_);

  AsyncCertIssuerSourceStatic async_certs2;
  async_certs2.AddCert(f_by_e_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&async_certs1);
  path_builder.AddCertIssuerSource(&async_certs2);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::ASYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  EXPECT_EQ(1, async_certs1.num_async_gets());
  EXPECT_EQ(1, async_certs2.num_async_gets());
}

// Test that PathBuilder does not generate longer paths than necessary if one of
// the supplied certs is itself a trust anchor.
TEST_F(PathBuilderMultiRootTest, TestLongChain) {
  // Both D(D) and C(D) are trusted roots.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(d_by_d_);
  trust_store.AddTrustedCertificate(c_by_d_);

  // Certs B(C), and C(D) are all supplied.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(b_by_c_);
  sync_certs.AddCert(c_by_d_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  // The result path should be A(B) <- B(C) <- C(D)
  // not the longer but also valid A(B) <- B(C) <- C(D) <- D(D)
  EXPECT_EQ(3U, result.paths[result.best_result_index]->path.size());
}

// Test that PathBuilder will backtrack and try a different path if the first
// one doesn't work out.
TEST_F(PathBuilderMultiRootTest, TestBacktracking) {
  // Only D(D) is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(d_by_d_);

  // Certs B(F) and F(E) are supplied synchronously, thus the path
  // A(B) <- B(F) <- F(E) should be built first, though it won't verify.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(b_by_f_);
  sync_certs.AddCert(f_by_e_);

  // Certs B(C), and C(D) are supplied asynchronously, so the path
  // A(B) <- B(C) <- C(D) <- D(D) should be tried second.
  AsyncCertIssuerSourceStatic async_certs;
  async_certs.AddCert(b_by_c_);
  async_certs.AddCert(c_by_d_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);
  path_builder.AddCertIssuerSource(&async_certs);

  EXPECT_EQ(CompletionStatus::ASYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  // The result path should be A(B) <- B(C) <- C(D) <- D(D)
  ASSERT_EQ(4U, result.paths[result.best_result_index]->path.size());
  EXPECT_EQ(a_by_b_, result.paths[result.best_result_index]->path[0]);
  EXPECT_EQ(b_by_c_, result.paths[result.best_result_index]->path[1]);
  EXPECT_EQ(c_by_d_, result.paths[result.best_result_index]->path[2]);
  EXPECT_EQ(d_by_d_, result.paths[result.best_result_index]->path[3]);
}

// Test that whichever order CertIssuerSource returns the issuers, the path
// building still succeeds.
TEST_F(PathBuilderMultiRootTest, TestCertIssuerOrdering) {
  // Only D(D) is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(d_by_d_);

  for (bool reverse_order : {false, true}) {
    SCOPED_TRACE(reverse_order);
    std::vector<scoped_refptr<ParsedCertificate>> certs = {
        b_by_c_, b_by_f_, f_by_e_, c_by_d_, c_by_e_};
    CertIssuerSourceStatic sync_certs;
    if (reverse_order) {
      for (auto it = certs.rbegin(); it != certs.rend(); ++it)
        sync_certs.AddCert(*it);
    } else {
      for (const auto& cert : certs)
        sync_certs.AddCert(cert);
    }

    CertPathBuilder::Result result;
    CertPathBuilder path_builder(a_by_b_, &trust_store, &signature_policy_,
                                 time_, &result);
    path_builder.AddCertIssuerSource(&sync_certs);

    EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

    EXPECT_EQ(OK, result.error());

    // The result path should be A(B) <- B(C) <- C(D) <- D(D)
    ASSERT_EQ(4U, result.paths[result.best_result_index]->path.size());
    EXPECT_EQ(a_by_b_, result.paths[result.best_result_index]->path[0]);
    EXPECT_EQ(b_by_c_, result.paths[result.best_result_index]->path[1]);
    EXPECT_EQ(c_by_d_, result.paths[result.best_result_index]->path[2]);
    EXPECT_EQ(d_by_d_, result.paths[result.best_result_index]->path[3]);
  }
}

class PathBuilderKeyRolloverTest : public ::testing::Test {
 public:
  PathBuilderKeyRolloverTest() : signature_policy_(1024) {}

  void SetUp() override {
    std::vector<std::string> path;

    ReadVerifyCertChainTestFromFile(
        "net/data/verify_certificate_chain_unittest/key-rollover-oldchain.pem",
        &path, &oldroot_, &time_);
    ASSERT_EQ(2U, path.size());
    target_ = ParsedCertificate::CreateFromCertificateCopy(path[0], {});
    oldintermediate_ =
        ParsedCertificate::CreateFromCertificateCopy(path[1], {});
    ASSERT_TRUE(target_);
    ASSERT_TRUE(oldintermediate_);

    ReadVerifyCertChainTestFromFile(
        "net/data/verify_certificate_chain_unittest/"
        "key-rollover-longrolloverchain.pem",
        &path, &oldroot_, &time_);
    ASSERT_EQ(4U, path.size());
    newintermediate_ =
        ParsedCertificate::CreateFromCertificateCopy(path[1], {});
    newroot_ = ParsedCertificate::CreateFromCertificateCopy(path[2], {});
    newrootrollover_ =
        ParsedCertificate::CreateFromCertificateCopy(path[3], {});
    ASSERT_TRUE(newintermediate_);
    ASSERT_TRUE(newroot_);
    ASSERT_TRUE(newrootrollover_);
  }

 protected:
  //    oldroot-------->newrootrollover  newroot
  //       |                      |        |
  //       v                      v        v
  // oldintermediate           newintermediate
  //       |                          |
  //       +------------+-------------+
  //                    |
  //                    v
  //                  target
  scoped_refptr<ParsedCertificate> target_;
  scoped_refptr<ParsedCertificate> oldintermediate_;
  scoped_refptr<ParsedCertificate> newintermediate_;
  scoped_refptr<ParsedCertificate> oldroot_;
  scoped_refptr<ParsedCertificate> newroot_;
  scoped_refptr<ParsedCertificate> newrootrollover_;

  SimpleSignaturePolicy signature_policy_;
  der::GeneralizedTime time_;
};

// Tests that if only the old root cert is trusted, the path builder can build a
// path through the new intermediate and rollover cert to the old root.
TEST_F(PathBuilderKeyRolloverTest, TestRolloverOnlyOldRootTrusted) {
  // Only oldroot is trusted.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(oldroot_);

  // Old intermediate cert is not provided, so the pathbuilder will need to go
  // through the rollover cert.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(newintermediate_);
  sync_certs.AddCert(newrootrollover_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  // Path builder will first attempt: target <- newintermediate <- oldroot
  // but it will fail since newintermediate is signed by newroot.
  ASSERT_EQ(2U, result.paths.size());
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[0]->path[1]);
  EXPECT_EQ(oldroot_, result.paths[0]->path[2]);

  // Path builder will next attempt:
  // target <- newintermediate <- newrootrollover <- oldroot
  // which will succeed.
  EXPECT_EQ(1U, result.best_result_index);
  EXPECT_EQ(OK, result.paths[1]->error);
  ASSERT_EQ(4U, result.paths[1]->path.size());
  EXPECT_EQ(target_, result.paths[1]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[1]->path[1]);
  EXPECT_EQ(newrootrollover_, result.paths[1]->path[2]);
  EXPECT_EQ(oldroot_, result.paths[1]->path[3]);
}

// Tests that if both old and new roots are trusted it can build a path through
// either.
// TODO(mattm): Once prioritization is implemented, it should test that it
// always builds the path through the new intermediate and new root.
TEST_F(PathBuilderKeyRolloverTest, TestRolloverBothRootsTrusted) {
  // Both oldroot and newroot are trusted.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(oldroot_);
  trust_store.AddTrustedCertificate(newroot_);

  // Both old and new intermediates + rollover cert are provided.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(oldintermediate_);
  sync_certs.AddCert(newintermediate_);
  sync_certs.AddCert(newrootrollover_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  // Path builder willattempt one of:
  // target <- oldintermediate <- oldroot
  // target <- newintermediate <- newroot
  // either will succeed.
  ASSERT_EQ(1U, result.paths.size());
  EXPECT_EQ(OK, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  if (result.paths[0]->path[1] != newintermediate_) {
    DVLOG(1) << "USED OLD";
    EXPECT_EQ(oldintermediate_, result.paths[0]->path[1]);
    EXPECT_EQ(oldroot_, result.paths[0]->path[2]);
  } else {
    DVLOG(1) << "USED NEW";
    EXPECT_EQ(newintermediate_, result.paths[0]->path[1]);
    EXPECT_EQ(newroot_, result.paths[0]->path[2]);
  }
}

// Tests that multiple trust root matches on a single path will be considered.
// Both roots have the same subject but different keys. Only one of them will
// verify.
TEST_F(PathBuilderKeyRolloverTest, TestMultipleRootMatchesOnlyOneWorks) {
  // Both newroot and oldroot are trusted.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newroot_);
  trust_store.AddTrustedCertificate(oldroot_);

  // Only oldintermediate is supplied, so the path with newroot should fail,
  // oldroot should succeed.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(oldintermediate_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  // There may be one or two paths attempted depending if the path builder tried
  // using newroot first.
  // TODO(mattm): Once TrustStore is an interface, this could be fixed with a
  // mock version of TrustStore that returns roots in a deterministic order.
  ASSERT_LE(1U, result.paths.size());
  ASSERT_GE(2U, result.paths.size());

  if (result.paths.size() == 2) {
    // Path builder may first attempt: target <- oldintermediate <- newroot
    // but it will fail since oldintermediate is signed by oldroot.
    EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
    ASSERT_EQ(3U, result.paths[0]->path.size());
    EXPECT_EQ(target_, result.paths[0]->path[0]);
    EXPECT_EQ(oldintermediate_, result.paths[0]->path[1]);
    EXPECT_EQ(newroot_, result.paths[0]->path[2]);
  }

  // Path builder will next attempt:
  // target <- old intermediate <- oldroot
  // which should succeed.
  EXPECT_EQ(OK, result.paths[result.best_result_index]->error);
  ASSERT_EQ(3U, result.paths[result.best_result_index]->path.size());
  EXPECT_EQ(target_, result.paths[result.best_result_index]->path[0]);
  EXPECT_EQ(oldintermediate_, result.paths[result.best_result_index]->path[1]);
  EXPECT_EQ(oldroot_, result.paths[result.best_result_index]->path[2]);
}

// Tests that the path builder doesn't build longer than necessary paths.
TEST_F(PathBuilderKeyRolloverTest, TestRolloverLongChain) {
  // Only oldroot is trusted.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(oldroot_);

  // New intermediate and new root are provided synchronously.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(newintermediate_);
  sync_certs.AddCert(newroot_);

  // Rollover cert is only provided asynchronously. This will force the
  // pathbuilder to first try building a longer than necessary path.
  AsyncCertIssuerSourceStatic async_certs;
  async_certs.AddCert(newrootrollover_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);
  path_builder.AddCertIssuerSource(&async_certs);

  EXPECT_EQ(CompletionStatus::ASYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  ASSERT_EQ(3U, result.paths.size());

  // Path builder will first attempt: target <- newintermediate <- oldroot
  // but it will fail since newintermediate is signed by newroot.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[0]->path[1]);
  EXPECT_EQ(oldroot_, result.paths[0]->path[2]);

  // Path builder will next attempt:
  // target <- newintermediate <- newroot <- oldroot
  // but it will fail since newroot is self-signed.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[1]->error);
  ASSERT_EQ(4U, result.paths[1]->path.size());
  EXPECT_EQ(target_, result.paths[1]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[1]->path[1]);
  EXPECT_EQ(newroot_, result.paths[1]->path[2]);
  EXPECT_EQ(oldroot_, result.paths[1]->path[3]);

  // Path builder will skip:
  // target <- newintermediate <- newroot <- newrootrollover <- ...
  // Since newroot and newrootrollover have the same Name+SAN+SPKI.

  // Finally path builder will use:
  // target <- newintermediate <- newrootrollover <- oldroot
  EXPECT_EQ(2U, result.best_result_index);
  EXPECT_EQ(OK, result.paths[2]->error);
  ASSERT_EQ(4U, result.paths[2]->path.size());
  EXPECT_EQ(target_, result.paths[2]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[2]->path[1]);
  EXPECT_EQ(newrootrollover_, result.paths[2]->path[2]);
  EXPECT_EQ(oldroot_, result.paths[2]->path[3]);
}

// If the target cert is a trust root, that alone is a valid path.
TEST_F(PathBuilderKeyRolloverTest, TestEndEntityIsTrustRoot) {
  // Trust newintermediate.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newintermediate_);

  CertPathBuilder::Result result;
  // Newintermediate is also the target cert.
  CertPathBuilder path_builder(newintermediate_, &trust_store,
                               &signature_policy_, time_, &result);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  ASSERT_EQ(1U, result.paths.size());
  EXPECT_EQ(OK, result.paths[0]->error);
  ASSERT_EQ(1U, result.paths[0]->path.size());
  EXPECT_EQ(newintermediate_, result.paths[0]->path[0]);
}

// If target has same Name+SAN+SPKI as a necessary intermediate, test if a path
// can still be built.
// Since LoopChecker will prevent the intermediate from being included, this
// currently does NOT verify. This case shouldn't occur in the web PKI.
TEST_F(PathBuilderKeyRolloverTest,
       TestEndEntityHasSameNameAndSpkiAsIntermediate) {
  // Trust oldroot.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(oldroot_);

  // New root rollover is provided synchronously.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(newrootrollover_);

  CertPathBuilder::Result result;
  // Newroot is the target cert.
  CertPathBuilder path_builder(newroot_, &trust_store, &signature_policy_,
                               time_, &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  // This could actually be OK, but CertPathBuilder does not build the
  // newroot <- newrootrollover <- oldroot path.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.error());
}

// If target has same Name+SAN+SPKI as the trust root, test that a (trivial)
// path can still be built.
TEST_F(PathBuilderKeyRolloverTest,
       TestEndEntityHasSameNameAndSpkiAsTrustAnchor) {
  // Trust newrootrollover.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newrootrollover_);

  CertPathBuilder::Result result;
  // Newroot is the target cert.
  CertPathBuilder path_builder(newroot_, &trust_store, &signature_policy_,
                               time_, &result);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());

  ASSERT_FALSE(result.paths.empty());
  const CertPathBuilder::ResultPath* best_result =
      result.paths[result.best_result_index].get();

  // Newroot has same name+SPKI as newrootrollover, thus the path is valid and
  // only contains newroot.
  EXPECT_EQ(OK, best_result->error);
  ASSERT_EQ(1U, best_result->path.size());
  EXPECT_EQ(newroot_, best_result->path[0]);
}

// Test that PathBuilder will not try the same path twice if multiple
// CertIssuerSources provide the same certificate.
TEST_F(PathBuilderKeyRolloverTest, TestDuplicateIntermediates) {
  // Create a separate copy of oldintermediate.
  scoped_refptr<ParsedCertificate> oldintermediate_dupe(
      ParsedCertificate::CreateFromCertificateCopy(
          oldintermediate_->der_cert().AsStringPiece(), {}));

  // Only newroot is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newroot_);

  // The oldintermediate is supplied synchronously by |sync_certs1| and
  // another copy of oldintermediate is supplied synchronously by |sync_certs2|.
  // The path target <- oldintermediate <- newroot  should be built first,
  // though it won't verify. It should not be attempted again even though
  // oldintermediate was supplied twice.
  CertIssuerSourceStatic sync_certs1;
  sync_certs1.AddCert(oldintermediate_);
  CertIssuerSourceStatic sync_certs2;
  sync_certs2.AddCert(oldintermediate_dupe);

  // The newintermediate is supplied asynchronously, so the path
  // target <- newintermediate <- newroot should be tried second.
  AsyncCertIssuerSourceStatic async_certs;
  async_certs.AddCert(newintermediate_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs1);
  path_builder.AddCertIssuerSource(&sync_certs2);
  path_builder.AddCertIssuerSource(&async_certs);

  EXPECT_EQ(CompletionStatus::ASYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(OK, result.error());
  ASSERT_EQ(2U, result.paths.size());

  // Path builder will first attempt: target <- oldintermediate <- newroot
  // but it will fail since oldintermediate is signed by oldroot.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  // Compare the DER instead of ParsedCertificate pointer, don't care which copy
  // of oldintermediate was used in the path.
  EXPECT_EQ(oldintermediate_->der_cert(), result.paths[0]->path[1]->der_cert());
  EXPECT_EQ(newroot_, result.paths[0]->path[2]);

  // Path builder will next attempt: target <- newintermediate <- newroot
  // which will succeed.
  EXPECT_EQ(1U, result.best_result_index);
  EXPECT_EQ(OK, result.paths[1]->error);
  ASSERT_EQ(3U, result.paths[1]->path.size());
  EXPECT_EQ(target_, result.paths[1]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[1]->path[1]);
  EXPECT_EQ(newroot_, result.paths[1]->path[2]);
}

// Test that PathBuilder will not try the same path twice if the same cert is
// presented via a CertIssuerSources and a TrustAnchor.
TEST_F(PathBuilderKeyRolloverTest, TestDuplicateIntermediateAndRoot) {
  // Create a separate copy of newroot.
  scoped_refptr<ParsedCertificate> newroot_dupe(
      ParsedCertificate::CreateFromCertificateCopy(
          newroot_->der_cert().AsStringPiece(), {}));

  // Only newroot is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newroot_);

  // The oldintermediate and newroot are supplied synchronously by |sync_certs|.
  CertIssuerSourceStatic sync_certs;
  sync_certs.AddCert(oldintermediate_);
  sync_certs.AddCert(newroot_dupe);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&sync_certs);

  EXPECT_EQ(CompletionStatus::SYNC, RunPathBuilder(&path_builder));

  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.error());
  ASSERT_EQ(1U, result.paths.size());

  // Path builder attempt: target <- oldintermediate <- newroot
  // but it will fail since oldintermediate is signed by oldroot.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  EXPECT_EQ(oldintermediate_, result.paths[0]->path[1]);
  // Compare the DER instead of ParsedCertificate pointer, don't care which copy
  // of newroot was used in the path.
  EXPECT_EQ(newroot_->der_cert(), result.paths[0]->path[2]->der_cert());
}

class MockCertIssuerSourceRequest : public CertIssuerSource::Request {
 public:
  MOCK_METHOD1(GetNext, CompletionStatus(scoped_refptr<ParsedCertificate>*));
};

class MockCertIssuerSource : public CertIssuerSource {
 public:
  MOCK_METHOD2(SyncGetIssuersOf,
               void(const ParsedCertificate*, ParsedCertificateList*));
  MOCK_METHOD3(AsyncGetIssuersOf,
               void(const ParsedCertificate*,
                    const IssuerCallback&,
                    std::unique_ptr<Request>*));
};

// Helper class to pass the Request to the PathBuilder when it calls
// AsyncGetIssuersOf. (GoogleMock has a ByMove helper, but it apparently can
// only be used with Return, not SetArgPointee.)
class CertIssuerSourceRequestMover {
 public:
  CertIssuerSourceRequestMover(std::unique_ptr<CertIssuerSource::Request> req)
      : request_(std::move(req)) {}
  void MoveIt(const ParsedCertificate* cert,
              const CertIssuerSource::IssuerCallback& issuers_callback,
              std::unique_ptr<CertIssuerSource::Request>* out_req) {
    *out_req = std::move(request_);
  }

 private:
  std::unique_ptr<CertIssuerSource::Request> request_;
};

// Test that a single CertIssuerSource returning multiple async batches of
// issuers is handled correctly. Due to the StrictMocks, it also tests that path
// builder does not request issuers of certs that it shouldn't.
TEST_F(PathBuilderKeyRolloverTest, TestMultipleAsyncCallbacksFromSingleSource) {
  StrictMock<MockCertIssuerSource> cert_issuer_source;

  // Only newroot is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newroot_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&cert_issuer_source);

  CertIssuerSource::IssuerCallback target_issuers_callback;
  // Create the mock CertIssuerSource::Request...
  std::unique_ptr<StrictMock<MockCertIssuerSourceRequest>>
      target_issuers_req_owner(new StrictMock<MockCertIssuerSourceRequest>());
  // Keep a raw pointer to the Request...
  StrictMock<MockCertIssuerSourceRequest>* target_issuers_req =
      target_issuers_req_owner.get();
  // Setup helper class to pass ownership of the Request to the PathBuilder when
  // it calls AsyncGetIssuersOf.
  CertIssuerSourceRequestMover req_mover(std::move(target_issuers_req_owner));
  {
    ::testing::InSequence s;
    EXPECT_CALL(cert_issuer_source, SyncGetIssuersOf(target_.get(), _));
    EXPECT_CALL(cert_issuer_source, AsyncGetIssuersOf(target_.get(), _, _))
        .WillOnce(
            DoAll(SaveArg<1>(&target_issuers_callback),
                  Invoke(&req_mover, &CertIssuerSourceRequestMover::MoveIt)));
  }

  TestClosure callback;
  CompletionStatus rv = path_builder.Run(callback.closure());
  ASSERT_EQ(CompletionStatus::ASYNC, rv);

  ASSERT_FALSE(target_issuers_callback.is_null());

  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // First async batch: return oldintermediate_.
  EXPECT_CALL(*target_issuers_req, GetNext(_))
      .WillOnce(DoAll(SetArgPointee<0>(oldintermediate_),
                      Return(CompletionStatus::SYNC)))
      .WillOnce(
          DoAll(SetArgPointee<0>(nullptr), Return(CompletionStatus::ASYNC)));
  {
    ::testing::InSequence s;
    // oldintermediate_ does not create a valid path, so both sync and async
    // lookups are expected.
    EXPECT_CALL(cert_issuer_source,
                SyncGetIssuersOf(oldintermediate_.get(), _));
    EXPECT_CALL(cert_issuer_source,
                AsyncGetIssuersOf(oldintermediate_.get(), _, _));
  }
  target_issuers_callback.Run(target_issuers_req);
  ::testing::Mock::VerifyAndClearExpectations(target_issuers_req);
  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // Second async batch: return newintermediate_.
  EXPECT_CALL(*target_issuers_req, GetNext(_))
      .WillOnce(DoAll(SetArgPointee<0>(newintermediate_),
                      Return(CompletionStatus::SYNC)))
      .WillOnce(
          DoAll(SetArgPointee<0>(nullptr), Return(CompletionStatus::ASYNC)));
  // newroot_ is in the trust store, so this path will be completed
  // synchronously. AsyncGetIssuersOf will not be called on newintermediate_.
  EXPECT_CALL(cert_issuer_source, SyncGetIssuersOf(newintermediate_.get(), _));
  target_issuers_callback.Run(target_issuers_req);
  // Note that VerifyAndClearExpectations(target_issuers_req) is not called
  // here. PathBuilder could have destroyed it already, so just let the
  // expectations get checked by the destructor.
  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // Ensure pathbuilder finished and filled result.
  callback.WaitForResult();

  EXPECT_EQ(OK, result.error());
  ASSERT_EQ(2U, result.paths.size());

  // Path builder first attempts: target <- oldintermediate <- newroot
  // but it will fail since oldintermediate is signed by oldroot.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  EXPECT_EQ(oldintermediate_, result.paths[0]->path[1]);
  EXPECT_EQ(newroot_, result.paths[0]->path[2]);

  // After the second batch of async results, path builder will attempt:
  // target <- newintermediate <- newroot which will succeed.
  EXPECT_EQ(OK, result.paths[1]->error);
  ASSERT_EQ(3U, result.paths[1]->path.size());
  EXPECT_EQ(target_, result.paths[1]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[1]->path[1]);
  EXPECT_EQ(newroot_, result.paths[1]->path[2]);
}

// Test that PathBuilder will not try the same path twice if CertIssuerSources
// asynchronously provide the same certificate multiple times.
TEST_F(PathBuilderKeyRolloverTest, TestDuplicateAsyncIntermediates) {
  StrictMock<MockCertIssuerSource> cert_issuer_source;

  // Only newroot is a trusted root.
  TrustStore trust_store;
  trust_store.AddTrustedCertificate(newroot_);

  CertPathBuilder::Result result;
  CertPathBuilder path_builder(target_, &trust_store, &signature_policy_, time_,
                               &result);
  path_builder.AddCertIssuerSource(&cert_issuer_source);

  CertIssuerSource::IssuerCallback target_issuers_callback;
  // Create the mock CertIssuerSource::Request...
  std::unique_ptr<StrictMock<MockCertIssuerSourceRequest>>
      target_issuers_req_owner(new StrictMock<MockCertIssuerSourceRequest>());
  // Keep a raw pointer to the Request...
  StrictMock<MockCertIssuerSourceRequest>* target_issuers_req =
      target_issuers_req_owner.get();
  // Setup helper class to pass ownership of the Request to the PathBuilder when
  // it calls AsyncGetIssuersOf.
  CertIssuerSourceRequestMover req_mover(std::move(target_issuers_req_owner));
  {
    ::testing::InSequence s;
    EXPECT_CALL(cert_issuer_source, SyncGetIssuersOf(target_.get(), _));
    EXPECT_CALL(cert_issuer_source, AsyncGetIssuersOf(target_.get(), _, _))
        .WillOnce(
            DoAll(SaveArg<1>(&target_issuers_callback),
                  Invoke(&req_mover, &CertIssuerSourceRequestMover::MoveIt)));
  }

  TestClosure callback;
  CompletionStatus rv = path_builder.Run(callback.closure());
  ASSERT_EQ(CompletionStatus::ASYNC, rv);

  ASSERT_FALSE(target_issuers_callback.is_null());

  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // First async batch: return oldintermediate_.
  EXPECT_CALL(*target_issuers_req, GetNext(_))
      .WillOnce(DoAll(SetArgPointee<0>(oldintermediate_),
                      Return(CompletionStatus::SYNC)))
      .WillOnce(
          DoAll(SetArgPointee<0>(nullptr), Return(CompletionStatus::ASYNC)));
  {
    ::testing::InSequence s;
    // oldintermediate_ does not create a valid path, so both sync and async
    // lookups are expected.
    EXPECT_CALL(cert_issuer_source,
                SyncGetIssuersOf(oldintermediate_.get(), _));
    EXPECT_CALL(cert_issuer_source,
                AsyncGetIssuersOf(oldintermediate_.get(), _, _));
  }
  target_issuers_callback.Run(target_issuers_req);
  ::testing::Mock::VerifyAndClearExpectations(target_issuers_req);
  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // Second async batch: return a different copy of oldintermediate_ again.
  scoped_refptr<ParsedCertificate> oldintermediate_dupe(
      ParsedCertificate::CreateFromCertificateCopy(
          oldintermediate_->der_cert().AsStringPiece(), {}));
  EXPECT_CALL(*target_issuers_req, GetNext(_))
      .WillOnce(DoAll(SetArgPointee<0>(oldintermediate_dupe),
                      Return(CompletionStatus::SYNC)))
      .WillOnce(
          DoAll(SetArgPointee<0>(nullptr), Return(CompletionStatus::ASYNC)));
  target_issuers_callback.Run(target_issuers_req);
  // oldintermediate was already processed above, it should not generate any
  // more requests.
  ::testing::Mock::VerifyAndClearExpectations(target_issuers_req);
  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // Third async batch: return newintermediate_.
  EXPECT_CALL(*target_issuers_req, GetNext(_))
      .WillOnce(DoAll(SetArgPointee<0>(newintermediate_),
                      Return(CompletionStatus::SYNC)))
      .WillOnce(
          DoAll(SetArgPointee<0>(nullptr), Return(CompletionStatus::ASYNC)));
  // newroot_ is in the trust store, so this path will be completed
  // synchronously. AsyncGetIssuersOf will not be called on newintermediate_.
  EXPECT_CALL(cert_issuer_source, SyncGetIssuersOf(newintermediate_.get(), _));
  target_issuers_callback.Run(target_issuers_req);
  // Note that VerifyAndClearExpectations(target_issuers_req) is not called
  // here. PathBuilder could have destroyed it already, so just let the
  // expectations get checked by the destructor.
  ::testing::Mock::VerifyAndClearExpectations(&cert_issuer_source);

  // Ensure pathbuilder finished and filled result.
  callback.WaitForResult();

  EXPECT_EQ(OK, result.error());
  ASSERT_EQ(2U, result.paths.size());

  // Path builder first attempts: target <- oldintermediate <- newroot
  // but it will fail since oldintermediate is signed by oldroot.
  EXPECT_EQ(ERR_CERT_AUTHORITY_INVALID, result.paths[0]->error);
  ASSERT_EQ(3U, result.paths[0]->path.size());
  EXPECT_EQ(target_, result.paths[0]->path[0]);
  EXPECT_EQ(oldintermediate_, result.paths[0]->path[1]);
  EXPECT_EQ(newroot_, result.paths[0]->path[2]);

  // The second async result does not generate any path.

  // After the third batch of async results, path builder will attempt:
  // target <- newintermediate <- newroot which will succeed.
  EXPECT_EQ(OK, result.paths[1]->error);
  ASSERT_EQ(3U, result.paths[1]->path.size());
  EXPECT_EQ(target_, result.paths[1]->path[0]);
  EXPECT_EQ(newintermediate_, result.paths[1]->path[1]);
  EXPECT_EQ(newroot_, result.paths[1]->path[2]);
}

}  // namespace

}  // namespace net
