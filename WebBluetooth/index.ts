/**
 * index.ts
 *
 * Entry point for W4RP Web Bluetooth Bridge.
 * Handles exports and auto-installation on window object.
 */

import { W4RPBridge } from './BridgeBLE';

export * from './BridgeTypes';
export * from './BridgeBLE';

// =============================================================================
// Global Bridge Interface & Auto-Install
// =============================================================================

/**
 * Global window declaration for W4RPBridge.
 * 
 * On all platforms, consumers should use: window.W4RPBridge
 * - iOS: Native Swift bridge is injected by WKWebView
 * - Android: Native Kotlin bridge is injected by WebView
 * - Web: This TypeScript implementation auto-installs itself
 */
declare global {
    interface Window {
        /** Unified W4RP Bridge - available on all platforms */
        W4RPBridge: W4RPBridge;
    }
}

/**
 * Auto-install W4RPBridge on window if:
 * 1. We're in a browser environment
 * 2. No native bridge has been injected (iOS/Android)
 * 3. Web Bluetooth is supported
 * 
 * This ensures window.W4RPBridge is ALWAYS available, making the API
 * truly unified across iOS, Android, and Web.
 */
if (typeof window !== 'undefined') {
    // Only auto-install if no native bridge exists
    if (!window.W4RPBridge) {
        // Check Web Bluetooth support
        if ('bluetooth' in navigator) {
            window.W4RPBridge = new W4RPBridge();
            console.log('[W4RP] Bridge auto-installed (platform: web)');
        } else {
            console.warn('[W4RP] Web Bluetooth not supported in this browser');
        }
    } else {
        console.log(`[W4RP] Native bridge detected (platform: ${window.W4RPBridge.platform})`);
    }
}
