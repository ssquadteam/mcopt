#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

int main(int argc, const char **argv) {
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		MTLCompileOptions *options = [MTLCompileOptions new];
		options.languageVersion = MTLLanguageVersion3_0;
		int failed = 0;
		for (int i = 1; i < argc; i++) {
			NSString *path = [NSString stringWithUTF8String:argv[i]];
			NSString *source = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
			NSError *error = nil;
			id<MTLLibrary> library = [device newLibraryWithSource:source options:options error:&error];
			if (library == nil) {
				failed++;
				printf("FAIL %s\n%s\n", argv[i], [[error localizedDescription] UTF8String]);
			}
		}
		printf("metal: %d of %d failed\n", failed, argc - 1);
		return failed == 0 ? 0 : 1;
	}
}
