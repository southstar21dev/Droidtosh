// Exports one code-signing identity (certificate and private key) as a password-protected
// PKCS#12, so macOS asks for permission once, for that key only.
//
//   swift scripts/export-signing-identity.swift <name> <out.p12> <password> [keychain]
//
// <name> is the certificate's common name ("Droidtosh Dev"). Without [keychain], the default
// keychain search list is used. Used by scripts/upload-release-secrets.sh.
import Foundation
import Security

let args = CommandLine.arguments
guard args.count == 4 || args.count == 5 else {
    FileHandle.standardError.write(Data("usage: export-signing-identity.swift <name> <out.p12> <password> [keychain]\n".utf8))
    exit(2)
}
let (name, output, password) = (args[1], args[2], args[3])

func fail(_ message: String, _ status: OSStatus? = nil) -> Never {
    let detail = status.map { " (\(SecCopyErrorMessageString($0, nil) as String? ?? "\($0)"))" } ?? ""
    FileHandle.standardError.write(Data("export-signing-identity: \(message)\(detail)\n".utf8))
    exit(1)
}

var query: [String: Any] = [
    kSecClass as String: kSecClassIdentity,
    kSecMatchLimit as String: kSecMatchLimitAll,
    kSecReturnRef as String: true,
]
if args.count == 5 {
    var keychain: SecKeychain?
    let status = SecKeychainOpen(args[4], &keychain)
    guard status == errSecSuccess, let keychain else { fail("can't open \(args[4])", status) }
    query[kSecMatchSearchList as String] = [keychain]
}

var result: CFTypeRef?
let status = SecItemCopyMatching(query as CFDictionary, &result)
guard status == errSecSuccess, let identities = result as? [SecIdentity] else { fail("no identities found", status) }

// Only identities whose certificate is named exactly `name`; an ambiguous match is an error.
let matches = identities.filter { identity in
    var certificate: SecCertificate?
    guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess, let certificate else { return false }
    return SecCertificateCopySubjectSummary(certificate) as String? == name
}
guard matches.count == 1, let identity = matches.first else {
    fail(matches.isEmpty ? "no identity named \"\(name)\"" : "\(matches.count) identities are named \"\(name)\"; keep one")
}

var parameters = SecItemImportExportKeyParameters()
parameters.version = UInt32(SEC_KEY_IMPORT_EXPORT_PARAMS_VERSION)
parameters.passphrase = Unmanaged.passUnretained(password as CFString)
var exported: CFData?
let exportStatus = SecItemExport(identity, .formatPKCS12, [], &parameters, &exported)
guard exportStatus == errSecSuccess, let exported else { fail("export failed", exportStatus) }
do {
    try (exported as Data).write(to: URL(fileURLWithPath: output), options: .atomic)
} catch {
    fail("can't write \(output): \(error)")
}
