#import <Foundation/Foundation.h>
#import <OIDExternalUserAgentSession.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT BOOL TeddResumeExternalUserAgentFlow(
    id<OIDExternalUserAgentSession> authorizationFlow,
    NSURL *URL
);

NS_ASSUME_NONNULL_END
