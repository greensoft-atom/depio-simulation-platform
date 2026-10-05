// The account's key in the iOS keychain (docs 08 §8), for Runtime/SecureStores.cs's KeychainStore: a generic
// password of this app's service, kept on this device only, readable after its first unlock. Not compiled on the
// development machine, which has no iOS SDK: Xcode builds it with the app.
#import <Foundation/Foundation.h>
#import <Security/Security.h>
#include <stdlib.h>
#include <string.h>

static NSString *const BackendService = @"com.backend.client";

static NSMutableDictionary *BackendQuery(const char *key)
{
    NSMutableDictionary *query = [NSMutableDictionary dictionary];
    query[(__bridge id)kSecClass] = (__bridge id)kSecClassGenericPassword;
    query[(__bridge id)kSecAttrService] = BackendService;
    query[(__bridge id)kSecAttrAccount] = [NSString stringWithUTF8String:key];
    return query;
}

extern "C" {

// A copy the caller frees with BackendKeychainFree, or NULL for none.
char *BackendKeychainGet(const char *key)
{
    NSMutableDictionary *query = BackendQuery(key);
    query[(__bridge id)kSecReturnData] = @YES;
    query[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
    CFTypeRef found = NULL;
    if (SecItemCopyMatching((__bridge CFDictionaryRef)query, &found) != errSecSuccess || found == NULL) return NULL;
    NSData *data = (__bridge_transfer NSData *)found;
    char *copy = (char *)malloc(data.length + 1);
    memcpy(copy, data.bytes, data.length);
    copy[data.length] = '\0';
    return copy;
}

void BackendKeychainDelete(const char *key)
{
    SecItemDelete((__bridge CFDictionaryRef)BackendQuery(key));
}

void BackendKeychainSet(const char *key, const char *value)
{
    BackendKeychainDelete(key);
    NSMutableDictionary *item = BackendQuery(key);
    item[(__bridge id)kSecValueData] = [NSData dataWithBytes:value length:strlen(value)];
    item[(__bridge id)kSecAttrAccessible] = (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;
    SecItemAdd((__bridge CFDictionaryRef)item, NULL);
}

void BackendKeychainFree(char *value)
{
    free(value);
}

}
