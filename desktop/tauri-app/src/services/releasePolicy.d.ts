export function stableVersion(value: unknown): number[] | null;
export function newerRelease(current: string, latest: string): boolean;
export function companionWarning(phone: { controlProtocol?: number; streamProtocol?: number; minimumCompanionVersion?: string } | null, currentVersion?: string): string;
