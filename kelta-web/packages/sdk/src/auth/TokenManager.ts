import type { TokenProvider } from '../client/types';
import { AuthenticationError } from '../errors';

/**
 * Token state interface
 */
interface TokenState {
  token: string | null;
  expiresAt: number | null;
}

/**
 * Manages authentication tokens with automatic refresh
 */
export class TokenManager {
  private state: TokenState = {
    token: null,
    expiresAt: null,
  };

  constructor(private readonly provider: TokenProvider) {}

  /**
   * Get a valid token, refreshing if necessary
   */
  async getValidToken(): Promise<string | null> {
    // If token is expired and we can refresh, do so
    if (this.isExpired() && this.provider.refreshToken) {
      await this.refreshIfNeeded();
    }

    // Get fresh token from provider
    const token = await this.provider.getToken();

    if (token) {
      this.state.token = token;
      // Parse expiration from JWT if possible
      this.state.expiresAt = this.parseTokenExpiration(token);
    }

    return token;
  }

  /**
   * Check if the current token is expired
   */
  private isExpired(): boolean {
    if (!this.state.expiresAt) {
      return false;
    }
    // Consider token expired 30 seconds before actual expiration
    return Date.now() >= this.state.expiresAt - 30000;
  }

  /**
   * Refresh the token if needed
   */
  private async refreshIfNeeded(): Promise<void> {
    if (!this.provider.refreshToken) {
      return;
    }

    try {
      const newToken = await this.provider.refreshToken();
      this.state.token = newToken;
      this.state.expiresAt = this.parseTokenExpiration(newToken);
    } catch {
      // Clear state on refresh failure
      this.state.token = null;
      this.state.expiresAt = null;
      throw new AuthenticationError('Token refresh failed');
    }
  }

  /**
   * Parse expiration time from a JWT token
   */
  private parseTokenExpiration(token: string): number | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }

      const payload = JSON.parse(atob(parts[1])) as { exp?: number };
      if (typeof payload.exp === 'number') {
        return payload.exp * 1000; // Convert to milliseconds
      }
      return null;
    } catch {
      return null;
    }
  }

  /**
   * Clear the cached token state
   */
  clearToken(): void {
    this.state.token = null;
    this.state.expiresAt = null;
  }
}
