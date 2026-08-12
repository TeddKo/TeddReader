#import "GoogleDrivePickerBridge.h"

BOOL TeddResumeExternalUserAgentFlow(
    id<OIDExternalUserAgentSession> authorizationFlow,
    NSURL *URL
) {
    return [authorizationFlow resumeExternalUserAgentFlowWithURL:URL error:nil];
}
