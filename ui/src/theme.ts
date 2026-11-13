import {useEffect, useState} from 'react';

export type ThemePreference = 'light' | 'dark' | 'system';
export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'lartifactory.theme';
const media = window.matchMedia('(prefers-color-scheme: dark)');
const listeners = new Set<() => void>();

function readPreference(): ThemePreference {
	try {
		const value = localStorage.getItem(STORAGE_KEY);
		return value === 'light' || value === 'dark' ? value : 'system';
	} catch {
		return 'system';
	}
}

let preference: ThemePreference = readPreference();

function resolve(value: ThemePreference): Theme {
	return value === 'system' ? (media.matches ? 'dark' : 'light') : value;
}

function apply(): void {
	document.documentElement.dataset.theme = resolve(preference);
	listeners.forEach(listener => listener());
}

media.addEventListener('change', () => preference === 'system' && apply());
apply();

export function setThemePreference(value: ThemePreference): void {
	preference = value;
	try {
		if (value === 'system') {
			localStorage.removeItem(STORAGE_KEY);
		} else {
			localStorage.setItem(STORAGE_KEY, value);
		}
	} catch {
		// Storage unavailable, the theme only applies to this page
	}
	apply();
}

/**
 * The stored theme preference and the theme it currently resolves to.
 */
export function useTheme(): { preference: ThemePreference; theme: Theme; setPreference: (value: ThemePreference) => void } {
	const [, setCounter] = useState(0);
	useEffect(() => {
		const listener = () => setCounter(value => value + 1);
		listeners.add(listener);
		return () => {
			listeners.delete(listener);
		};
	}, []);
	return {preference, theme: resolve(preference), setPreference: setThemePreference};
}

export const ACCENTS = ['indigo', 'blue', 'teal', 'green', 'amber', 'rose', 'violet'] as const;
export type Accent = (typeof ACCENTS)[number];
export type Density = 'comfortable' | 'compact';

/**
 * A preference stored in localStorage and applied as a data attribute on the root element, theme-init.js applies it before the first paint.
 */
function storedAttribute<T extends string>(key: string, attribute: 'accent' | 'density', values: readonly T[], fallback: T) {
	let value: T = fallback;
	try {
		const stored = localStorage.getItem(key) as T | null;
		value = stored && values.includes(stored) ? stored : fallback;
	} catch {
		// Storage unavailable, the default is used
	}
	const subscribers = new Set<() => void>();
	const applyValue = () => {
		document.documentElement.dataset[attribute] = value;
		subscribers.forEach(subscriber => subscriber());
	};
	applyValue();
	const set = (next: T) => {
		value = next;
		try {
			if (next === fallback) {
				localStorage.removeItem(key);
			} else {
				localStorage.setItem(key, next);
			}
		} catch {
			// Storage unavailable, the preference only applies to this page
		}
		applyValue();
	};
	return function usePreference(): [T, (next: T) => void] {
		const [, setCounter] = useState(0);
		useEffect(() => {
			const subscriber = () => setCounter(count => count + 1);
			subscribers.add(subscriber);
			return () => {
				subscribers.delete(subscriber);
			};
		}, []);
		return [value, set];
	};
}

export const useAccent = storedAttribute<Accent>('lartifactory.accent', 'accent', ACCENTS, 'indigo');
export const useDensity = storedAttribute<Density>('lartifactory.density', 'density', ['comfortable', 'compact'], 'comfortable');
