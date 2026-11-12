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
