import type {ReactNode} from 'react';

/**
 * Tokens of the snippets the ui shows (shell commands, Gradle/Kotlin, XML, TOML, ini), in order of precedence.
 * Not a full parser: it only colours what helps reading, strings, tags, sections, flags, comments and the placeholders to replace.
 */
const TOKEN = new RegExp([
	/(?<comment>(?:^|(?<=\s))(?:#|\/\/)[^\n]*)/.source,
	/(?<placeholder><token>)/.source,
	/(?<string>"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')/.source,
	/(?<tag><\/?[A-Za-z][\w.:-]*|\/?>)/.source,
	/(?<section>^\[[^\]\n]+\])/.source,
	/(?<flag>(?<=\s)--?[A-Za-z][\w-]*)/.source,
].join('|'), 'gm');

const PLACEHOLDER = /(<token>)/;

function withPlaceholders(text: string, key: string): ReactNode[] {
	return text.split(PLACEHOLDER).map((part, index) => part === '<token>'
		? <span key={`${key}-${index}`} className="tok-placeholder">{part}</span>
		: part);
}

export function highlight(code: string): ReactNode[] {
	const result: ReactNode[] = [];
	let last = 0;
	for (const match of code.matchAll(TOKEN)) {
		const index = match.index ?? 0;
		if (index > last) {
			result.push(code.substring(last, index));
		}
		const kind = Object.entries(match.groups ?? {}).find(([, value]) => value !== undefined)?.[0] ?? 'text';
		const key = `${index}`;
		result.push(kind === 'placeholder'
			? <span key={key} className="tok-placeholder">{match[0]}</span>
			: <span key={key} className={`tok-${kind}`}>{kind === 'string' ? withPlaceholders(match[0], key) : match[0]}</span>);
		last = index + match[0].length;
	}
	if (last < code.length) {
		result.push(code.substring(last));
	}
	return result;
}
