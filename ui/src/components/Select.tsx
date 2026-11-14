import {type KeyboardEvent, type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState} from 'react';
import {Icon} from './icons';

export interface SelectOption<T extends string> {
	value: T;
	label: ReactNode;
	/** Plain text of the label, used for type-ahead if the label is not a string */
	text?: string;
	/** Secondary text shown right aligned in the popup */
	hint?: ReactNode;
	disabled?: boolean;
}

/**
 * Dropdown rendered by the application instead of the browser, native select popups ignore the theme.
 * The popup is positioned fixed so scrolling containers and modal bodies do not clip it.
 */
export function Select<T extends string>({value, onChange, options, label, placeholder, className, disabled}: {
	value: T;
	onChange: (value: T) => void;
	options: SelectOption<T>[];
	label: string;
	placeholder?: string;
	className?: string;
	disabled?: boolean;
}) {
	const [open, setOpen] = useState(false);
	const [active, setActive] = useState(-1);
	const [position, setPosition] = useState<{ top: number; left: number; minWidth: number; maxHeight: number }>({top: 0, left: 0, minWidth: 0, maxHeight: 320});
	const button = useRef<HTMLButtonElement>(null);
	const popup = useRef<HTMLDivElement>(null);
	const typed = useRef({text: '', time: 0});
	const id = useId();
	const selected = options.find(option => option.value === value);
	
	const textOf = (option: SelectOption<T>) => option.text ?? (typeof option.label === 'string' ? option.label : String(option.value));
	const enabledIndex = (start: number, step: number) => {
		for (let i = start; i >= 0 && i < options.length; i += step) {
			if (!options[i].disabled) {
				return i;
			}
		}
		return -1;
	};
	
	const show = () => {
		if (disabled) {
			return;
		}
		const index = options.findIndex(option => option.value === value);
		setActive(index >= 0 ? index : enabledIndex(0, 1));
		setOpen(true);
	};
	const choose = (index: number) => {
		const option = options[index];
		if (option && !option.disabled) {
			onChange(option.value);
			setOpen(false);
			button.current?.focus();
		}
	};
	
	useLayoutEffect(() => {
		if (!open || !button.current || !popup.current) {
			return;
		}
		const rect = button.current.getBoundingClientRect();
		const below = window.innerHeight - rect.bottom - 12;
		const above = rect.top - 12;
		const height = Math.min(popup.current.scrollHeight, 320);
		const openBelow = height <= below || below >= above;
		const maxHeight = Math.min(320, openBelow ? below : above);
		setPosition({
			top: openBelow ? rect.bottom + 4 : rect.top - 4 - Math.min(height, maxHeight),
			left: Math.min(rect.left, window.innerWidth - Math.max(rect.width, popup.current.offsetWidth) - 8),
			minWidth: rect.width,
			maxHeight,
		});
	}, [open, options.length]);
	
	useEffect(() => {
		if (open && active >= 0) {
			popup.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({block: 'nearest'});
		}
	}, [open, active]);
	
	useEffect(() => {
		if (!open) {
			return;
		}
		const close = (event: Event) => {
			if (event.type === 'mousedown' && (popup.current?.contains(event.target as Node) || button.current?.contains(event.target as Node))) {
				return;
			}
			if (event.type === 'scroll' && popup.current?.contains(event.target as Node)) {
				return;
			}
			setOpen(false);
		};
		document.addEventListener('mousedown', close);
		window.addEventListener('resize', close);
		window.addEventListener('scroll', close, true);
		return () => {
			document.removeEventListener('mousedown', close);
			window.removeEventListener('resize', close);
			window.removeEventListener('scroll', close, true);
		};
	}, [open]);
	
	const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
		const key = event.key;
		if (!open) {
			if (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Enter' || key === ' ') {
				event.preventDefault();
				show();
			}
			return;
		}
		if (key === 'Escape') {
			// Only close the popup, not a surrounding modal
			event.preventDefault();
			event.stopPropagation();
			setOpen(false);
		} else if (key === 'Tab') {
			setOpen(false);
		} else if (key === 'ArrowDown' || key === 'ArrowUp') {
			event.preventDefault();
			const next = enabledIndex(active + (key === 'ArrowDown' ? 1 : -1), key === 'ArrowDown' ? 1 : -1);
			if (next >= 0) {
				setActive(next);
			}
		} else if (key === 'Home' || key === 'End') {
			event.preventDefault();
			setActive(key === 'Home' ? enabledIndex(0, 1) : enabledIndex(options.length - 1, -1));
		} else if (key === 'Enter' || key === ' ') {
			event.preventDefault();
			choose(active);
		} else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
			// Type-ahead, consecutive keys within a second extend the search text
			const now = Date.now();
			typed.current = {text: (now - typed.current.time < 1000 ? typed.current.text : '') + key.toLowerCase(), time: now};
			const match = options.findIndex(option => !option.disabled && textOf(option).toLowerCase().startsWith(typed.current.text));
			if (match >= 0) {
				setActive(match);
			}
		}
	};
	
	return (
		<>
			<button ref={button} type="button" className={`select${open ? ' select-open' : ''}${className ? ' ' + className : ''}`} disabled={disabled}
					aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? id : undefined} aria-label={label}
					aria-activedescendant={open && active >= 0 ? `${id}-${active}` : undefined}
					onClick={() => (open ? setOpen(false) : show())} onKeyDown={onKeyDown}>
				<span className={`select-value${selected ? '' : ' faint'}`}>{selected?.label ?? placeholder ?? ''}</span>
				<Icon name="chevron-down" size={16}/>
			</button>
			{open && (
				<div ref={popup} id={id} className="select-popup" role="listbox" aria-label={label} style={position}>
					{options.map((option, index) => (
						<div key={option.value} id={`${id}-${index}`} data-index={index} role="option" aria-selected={option.value === value} aria-disabled={option.disabled}
							 className={`select-option${index === active ? ' select-option-active' : ''}${option.disabled ? ' select-option-disabled' : ''}`}
							 onMouseEnter={() => !option.disabled && setActive(index)}
							 onMouseDown={event => event.preventDefault()}
							 onClick={() => choose(index)}>
							<span className="select-check" aria-hidden>{option.value === value && <Icon name="check" size={14}/>}</span>
							<span className="select-label">{option.label}</span>
							{option.hint && <span className="select-hint">{option.hint}</span>}
						</div>
					))}
					{options.length === 0 && <div className="select-empty">No options</div>}
				</div>
			)}
		</>
	);
}
