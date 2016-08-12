// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef NET_CERT_INTERNAL_VERIFY_CERTIFICATE_CHAIN_TYPED_UNITTEST_H_
#define NET_CERT_INTERNAL_VERIFY_CERTIFICATE_CHAIN_TYPED_UNITTEST_H_

#include "base/base_paths.h"
#include "base/files/file_util.h"
#include "base/path_service.h"
#include "net/cert/internal/parsed_certificate.h"
#include "net/cert/internal/test_helpers.h"
#include "net/cert/pem_tokenizer.h"
#include "net/der/input.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace net {

template <typename TestDelegate>
class VerifyCertificateChainTest : public ::testing::Test {
 public:
  void RunTest(const char* file_name) {
    ParsedCertificateList chain;
    ParsedCertificateList roots;
    der::GeneralizedTime time;
    bool expected_result;

    ReadTestFromFile(file_name, &chain, &roots, &time, &expected_result);

    TestDelegate::Verify(chain, roots, time, expected_result);
  }

 private:
  // Reads a data file from the unit-test data.
  std::string ReadTestFileToString(const std::string& file_name) {
    // Compute the full path, relative to the src/ directory.
    base::FilePath src_root;
    PathService::Get(base::DIR_SOURCE_ROOT, &src_root);
    base::FilePath filepath = src_root.AppendASCII(
        std::string("net/data/verify_certificate_chain_unittest/") + file_name);

    // Read the full contents of the file.
    std::string file_data;
    if (!base::ReadFileToString(filepath, &file_data)) {
      ADD_FAILURE() << "Couldn't read file: " << filepath.value();
      return std::string();
    }

    return file_data;
  }

  // Reads a test case from |file_name|. Test cases are comprised of a
  // certificate chain, trust store, a timestamp to validate at, and the
  // expected result of verification.
  void ReadTestFromFile(const std::string& file_name,
                        ParsedCertificateList* chain,
                        ParsedCertificateList* roots,
                        der::GeneralizedTime* time,
                        bool* verify_result) {
    chain->clear();
    roots->clear();

    std::string file_data = ReadTestFileToString(file_name);

    std::vector<std::string> pem_headers;

    const char kCertificateHeader[] = "CERTIFICATE";
    const char kTrustedCertificateHeader[] = "TRUSTED_CERTIFICATE";
    const char kTimeHeader[] = "TIME";
    const char kResultHeader[] = "VERIFY_RESULT";

    pem_headers.push_back(kCertificateHeader);
    pem_headers.push_back(kTrustedCertificateHeader);
    pem_headers.push_back(kTimeHeader);
    pem_headers.push_back(kResultHeader);

    bool has_time = false;
    bool has_result = false;

    PEMTokenizer pem_tokenizer(file_data, pem_headers);
    while (pem_tokenizer.GetNext()) {
      const std::string& block_type = pem_tokenizer.block_type();
      const std::string& block_data = pem_tokenizer.data();

      if (block_type == kCertificateHeader) {
        ASSERT_TRUE(net::ParsedCertificate::CreateAndAddToVector(
            reinterpret_cast<const uint8_t*>(block_data.data()),
            block_data.size(),
            net::ParsedCertificate::DataSource::INTERNAL_COPY, {}, chain));
      } else if (block_type == kTrustedCertificateHeader) {
        ASSERT_TRUE(net::ParsedCertificate::CreateAndAddToVector(
            reinterpret_cast<const uint8_t*>(block_data.data()),
            block_data.size(),
            net::ParsedCertificate::DataSource::INTERNAL_COPY, {}, roots));
      } else if (block_type == kTimeHeader) {
        ASSERT_FALSE(has_time) << "Duplicate " << kTimeHeader;
        has_time = true;
        ASSERT_TRUE(der::ParseUTCTime(der::Input(&block_data), time));
      } else if (block_type == kResultHeader) {
        ASSERT_FALSE(has_result) << "Duplicate " << kResultHeader;
        ASSERT_TRUE(block_data == "SUCCESS" || block_data == "FAIL")
            << "Unrecognized result: " << block_data;
        has_result = true;
        *verify_result = block_data == "SUCCESS";
      }
    }

    ASSERT_TRUE(has_time);
    ASSERT_TRUE(has_result);
  }
};

// Tests that have only one root. These can be tested without requiring any
// path-building ability.
template <typename TestDelegate>
class VerifyCertificateChainSingleRootTest
    : public VerifyCertificateChainTest<TestDelegate> {};

TYPED_TEST_CASE_P(VerifyCertificateChainSingleRootTest);

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetAndIntermediate) {
  this->RunTest("target-and-intermediate.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateLacksBasicConstraints) {
  this->RunTest("intermediate-lacks-basic-constraints.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateBasicConstraintsCaFalse) {
  this->RunTest("intermediate-basic-constraints-ca-false.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateBasicConstraintsNotCritical) {
  this->RunTest("intermediate-basic-constraints-not-critical.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateLacksSigningKeyUsage) {
  this->RunTest("intermediate-lacks-signing-key-usage.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateUnknownCriticalExtension) {
  this->RunTest("intermediate-unknown-critical-extension.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IntermediateUnknownNonCriticalExtension) {
  this->RunTest("intermediate-unknown-non-critical-extension.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             ViolatesBasicConstraintsPathlen0) {
  this->RunTest("violates-basic-constraints-pathlen-0.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             BasicConstraintsPathlen0SelfIssued) {
  this->RunTest("basic-constraints-pathlen-0-self-issued.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetSignedWithMd5) {
  this->RunTest("target-signed-with-md5.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, IntermediateSignedWithMd5) {
  this->RunTest("intermediate-signed-with-md5.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetWrongSignature) {
  this->RunTest("target-wrong-signature.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetSignedBy512bitRsa) {
  this->RunTest("target-signed-by-512bit-rsa.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetSignedUsingEcdsa) {
  this->RunTest("target-signed-using-ecdsa.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, ExpiredIntermediate) {
  this->RunTest("expired-intermediate.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, ExpiredTarget) {
  this->RunTest("expired-target.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, ExpiredTargetNotBefore) {
  this->RunTest("expired-target-notBefore.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, ExpiredRoot) {
  this->RunTest("expired-root.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetNotEndEntity) {
  this->RunTest("target-not-end-entity.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             TargetHasKeyCertSignButNotCa) {
  this->RunTest("target-has-keycertsign-but-not-ca.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, TargetHasPathlenButNotCa) {
  this->RunTest("target-has-pathlen-but-not-ca.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             TargetUnknownCriticalExtension) {
  this->RunTest("target-unknown-critical-extension.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IssuerAndSubjectNotByteForByteEqual) {
  this->RunTest("issuer-and-subject-not-byte-for-byte-equal.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             IssuerAndSubjectNotByteForByteEqualAnchor) {
  this->RunTest("issuer-and-subject-not-byte-for-byte-equal-anchor.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, ViolatesPathlen1Root) {
  this->RunTest("violates-pathlen-1-root.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, NonSelfSignedRoot) {
  this->RunTest("non-self-signed-root.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, KeyRolloverOldChain) {
  this->RunTest("key-rollover-oldchain.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, KeyRolloverRolloverChain) {
  this->RunTest("key-rollover-rolloverchain.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest,
             KeyRolloverLongRolloverChain) {
  this->RunTest("key-rollover-longrolloverchain.pem");
}

TYPED_TEST_P(VerifyCertificateChainSingleRootTest, KeyRolloverNewChain) {
  this->RunTest("key-rollover-newchain.pem");
}

// TODO(eroman): Add test that invalidate validity dates where the day or month
// ordinal not in range, like "March 39, 2016" are rejected.

REGISTER_TYPED_TEST_CASE_P(VerifyCertificateChainSingleRootTest,
                           TargetAndIntermediate,
                           IntermediateLacksBasicConstraints,
                           IntermediateBasicConstraintsCaFalse,
                           IntermediateBasicConstraintsNotCritical,
                           IntermediateLacksSigningKeyUsage,
                           IntermediateUnknownCriticalExtension,
                           IntermediateUnknownNonCriticalExtension,
                           ViolatesBasicConstraintsPathlen0,
                           BasicConstraintsPathlen0SelfIssued,
                           TargetSignedWithMd5,
                           IntermediateSignedWithMd5,
                           TargetWrongSignature,
                           TargetSignedBy512bitRsa,
                           TargetSignedUsingEcdsa,
                           ExpiredIntermediate,
                           ExpiredTarget,
                           ExpiredTargetNotBefore,
                           ExpiredRoot,
                           TargetNotEndEntity,
                           TargetHasKeyCertSignButNotCa,
                           TargetHasPathlenButNotCa,
                           TargetUnknownCriticalExtension,
                           IssuerAndSubjectNotByteForByteEqual,
                           IssuerAndSubjectNotByteForByteEqualAnchor,
                           ViolatesPathlen1Root,
                           NonSelfSignedRoot,
                           KeyRolloverOldChain,
                           KeyRolloverRolloverChain,
                           KeyRolloverLongRolloverChain,
                           KeyRolloverNewChain);

// Tests that have zero roots or more than one root.
template <typename TestDelegate>
class VerifyCertificateChainNonSingleRootTest
    : public VerifyCertificateChainTest<TestDelegate> {};

TYPED_TEST_CASE_P(VerifyCertificateChainNonSingleRootTest);

TYPED_TEST_P(VerifyCertificateChainNonSingleRootTest, UnknownRoot) {
  this->RunTest("unknown-root.pem");
}

REGISTER_TYPED_TEST_CASE_P(VerifyCertificateChainNonSingleRootTest,
                           UnknownRoot);

}  // namespace net

#endif  // NET_CERT_INTERNAL_VERIFY_CERTIFICATE_CHAIN_TYPED_UNITTEST_H_
