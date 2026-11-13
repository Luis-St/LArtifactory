import {type ButtonHTMLAttributes, type ReactNode, useEffect, useLayoutEffect, useRef, useState} from 'react';
import type {RepositoryType} from '../types';
import {highlight} from './highlight';
import {Icon, type IconName} from './icons';

export const TYPE_INFO: Record<RepositoryType, { label: string; ecosystem: string; color: string }> = {
	maven: {label: 'Maven', ecosystem: 'Maven & Gradle', color: 'var(--type-maven)'},
	generic: {label: 'Generic', ecosystem: 'Arbitrary files', color: 'var(--type-generic)'},
	pypi: {label: 'PyPI', ecosystem: 'pip & twine', color: 'var(--type-pypi)'},
	npm: {label: 'npm', ecosystem: 'npm, yarn & pnpm', color: 'var(--type-npm)'},
	nuget: {label: 'NuGet', ecosystem: 'dotnet & nuget', color: 'var(--type-nuget)'},
	cargo: {label: 'Cargo', ecosystem: 'Rust crates', color: 'var(--type-cargo)'},
	oci: {label: 'OCI', ecosystem: 'Docker & OCI images', color: 'var(--type-oci)'},
};

export function TypeBadge({type}: { type: RepositoryType }) {
	return <span className="type-badge" style={{'--badge': TYPE_INFO[type]?.color} as React.CSSProperties}>{TYPE_INFO[type]?.label ?? type}</span>;
}

export function TypeIcon({type, size = 36}: { type: RepositoryType; size?: number }) {
	return (
		<span className="type-icon" style={{'--badge': TYPE_INFO[type]?.color, width: size, height: size} as React.CSSProperties} aria-hidden>
			{(TYPE_INFO[type]?.label ?? type).substring(0, 2)}
		</span>
	);
}

export function Badge({children, tone = 'neutral', className, title}: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'accent'; className?: string; title?: string }) {
	return <span className={`badge badge-${tone}${className ? ' ' + className : ''}`} title={title}>{children}</span>;
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' | 'ghost'; small?: boolean };

export function Button({variant = 'secondary', small, className, ...props}: ButtonProps) {
	return <button type="button" className={`btn btn-${variant}${small ? ' btn-small' : ''}${className ? ' ' + className : ''}`} {...props} />;
}

/**
 * Placeholder while content loads, shimmering bars instead of a spinner so the page does not jump when the content arrives.
 */
export function Spinner({label = 'Loading', rows = 4}: { label?: string; rows?: number }) {
	return (
		<div className="skeleton" role="status" aria-label={label}>
			{Array.from({length: rows}, (_, index) => <span key={index} className="skeleton-bar" style={{width: `${92 - ((index * 17) % 35)}%`}}/>)}
		</div>
	);
}

/**
 * Round avatar with the initial of a user, the hue is derived from the name so every user keeps the same colour.
 */
export function Avatar({name, size}: { name: string; size?: 'small' | 'large' }) {
	let hash = 0;
	for (const char of name) {
		hash = (hash * 31 + char.charCodeAt(0)) | 0;
	}
	return (
		<span className={`avatar${size ? ` avatar-${size}` : ''}`} style={{'--avatar-hue': Math.abs(hash) % 360} as React.CSSProperties} aria-hidden>
			{name.substring(0, 1).toUpperCase()}
		</span>
	);
}

export function ErrorBox({error, onRetry}: { error: Error | string; onRetry?: () => void }) {
	return (
		<div className="error-box" role="alert">
			<span>{typeof error === 'string' ? error : error.message}</span>
			{onRetry && <Button small onClick={onRetry}>Retry</Button>}
		</div>
	);
}

export function Empty({title, children}: { title: string; children?: ReactNode }) {
	return (
		<div className="empty">
			<strong>{title}</strong>
			{children && <div>{children}</div>}
		</div>
	);
}

export interface Crumb {
	label: ReactNode;
	href?: string;
}

/**
 * Page title with optional breadcrumbs above it, the last crumb is the current page and is not a link.
 */
export function PageHeader({title, subtitle, actions, crumbs}: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; crumbs?: Crumb[] }) {
	return (
		<header className="page-header">
			<div className="page-title">
				{crumbs && crumbs.length > 0 && (
					<nav className="page-crumbs" aria-label="Breadcrumb">
						<ol>
							{crumbs.map((crumb, index) => (
								<li key={index}>
									{index > 0 && <Icon name="chevron-right" size={13}/>}
									{crumb.href && index < crumbs.length - 1 ? <a href={crumb.href}>{crumb.label}</a> : <span aria-current={index === crumbs.length - 1 ? 'page' : undefined}>{crumb.label}</span>}
								</li>
							))}
						</ol>
					</nav>
				)}
				<h1>{title}</h1>
				{subtitle && <p className="muted">{subtitle}</p>}
			</div>
			{actions && <div className="page-actions">{actions}</div>}
		</header>
	);
}

export function Card({title, actions, children, className}: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
	return (
		<section className={`card${className ? ' ' + className : ''}`}>
			{(title || actions) && (
				<div className="card-header">
					{title && <h2>{title}</h2>}
					{actions}
				</div>
			)}
			{children}
		</section>
	);
}

export function Stats({children}: { children: ReactNode }) {
	return <div className="stats-row">{children}</div>;
}

export function Stat({label, value, hint}: { label: string; value: ReactNode; hint?: ReactNode }) {
	return (
		<div className="stat">
			<span className="stat-label">{label}</span>
			<span className="stat-value">{value}</span>
			{hint && <span className="stat-hint">{hint}</span>}
		</div>
	);
}

export function Modal({title, onClose, children, footer, wide}: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
	const dialog = useRef<HTMLDialogElement>(null);
	useEffect(() => {
		dialog.current?.showModal();
		// showModal focuses the close button, the first form control is where the user wants to type
		dialog.current?.querySelector<HTMLElement>('.modal-body :is(input, select, textarea):not(:disabled)')?.focus();
	}, []);
	return (
		<dialog ref={dialog} className={`modal${wide ? ' modal-wide' : ''}`} onCancel={event => {
			event.preventDefault();
			onClose();
		}}>
			<div className="modal-header">
				<h2>{title}</h2>
				<button type="button" className="icon-btn" onClick={onClose} aria-label="Close"><Icon name="close"/></button>
			</div>
			<div className="modal-body">{children}</div>
			{footer && <div className="modal-footer">{footer}</div>}
		</dialog>
	);
}

export function ConfirmDialog({title, message, confirmLabel = 'Delete', onConfirm, onClose}: {
	title: string;
	message: ReactNode;
	confirmLabel?: string;
	onConfirm: () => Promise<void>;
	onClose: () => void;
}) {
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const confirm = async () => {
		setBusy(true);
		setError(undefined);
		try {
			await onConfirm();
			onClose();
		} catch (e) {
			setError(e instanceof Error ? e.message : String(e));
			setBusy(false);
		}
	};
	return (
		<Modal title={title} onClose={onClose} footer={
			<>
				<Button onClick={onClose} disabled={busy}>Cancel</Button>
				<Button variant="danger" onClick={confirm} disabled={busy}>{busy ? 'Working…' : confirmLabel}</Button>
			</>
		}>
			<div className="confirm-message">{message}</div>
			{error && <ErrorBox error={error}/>}
		</Modal>
	);
}

export function CopyButton({value, label = 'Copy', iconOnly}: { value: string; label?: string; iconOnly?: boolean }) {
	const [copied, setCopied] = useState(false);
	const copy = async () => {
		try {
			await navigator.clipboard.writeText(value);
			setCopied(true);
			setTimeout(() => setCopied(false), 1500);
		} catch {
			// Clipboard access denied, the value stays selectable
		}
	};
	return (
		<Button small variant="ghost" className={iconOnly ? 'btn-icon' : undefined} onClick={copy} title={iconOnly ? (copied ? 'Copied' : label) : undefined} aria-label={label}>
			<Icon name={copied ? 'check' : 'copy'} size={15}/>
			{!iconOnly && <span>{copied ? 'Copied' : label}</span>}
		</Button>
	);
}

/**
 * Several code snippets shown one at a time, picked with tabs above the code.
 */
export function SnippetTabs({snippets}: { snippets: { title: string; code: string }[] }) {
	const [active, setActive] = useState(0);
	const snippet = snippets[Math.min(active, snippets.length - 1)];
	if (!snippet) {
		return null;
	}
	return (
		<div className="code-block">
			<div className="code-block-header">
				<div className="snippet-tabs" role="tablist">
					{snippets.map((entry, index) => (
						<button key={entry.title} type="button" role="tab" aria-selected={index === active} className={`snippet-tab${index === active ? ' snippet-tab-active' : ''}`} onClick={() => setActive(index)}>
							{entry.title}
						</button>
					))}
				</div>
				<CopyButton value={snippet.code}/>
			</div>
			<pre><code>{highlight(snippet.code)}</code></pre>
		</div>
	);
}

export function CodeBlock({title, code}: { title?: string; code: string }) {
	return (
		<div className="code-block">
			<div className="code-block-header">
				<span>{title}</span>
				<CopyButton value={code}/>
			</div>
			<pre><code>{highlight(code)}</code></pre>
		</div>
	);
}

export function Toggle({checked, onChange, label, description, disabled}: { checked: boolean; onChange: (value: boolean) => void; label: string; description?: string; disabled?: boolean }) {
	return (
		<label className="toggle">
			<input type="checkbox" checked={checked} onChange={event => onChange(event.target.checked)} disabled={disabled}/>
			<span className="toggle-track" aria-hidden><span className="toggle-thumb"/></span>
			<span className="toggle-text">
				<span>{label}</span>
				{description && <small className="muted">{description}</small>}
			</span>
		</label>
	);
}

/**
 * A labelled form control, group renders a plain container for controls that bring their own labels (checkbox lists).
 */
export function Field({label, hint, children, group}: { label: string; hint?: ReactNode; children: ReactNode; group?: boolean }) {
	const content = (
		<>
			<span className="field-label">{label}</span>
			{children}
			{hint && <small className="muted">{hint}</small>}
		</>
	);
	return group ? <div className="field" role="group" aria-label={label}>{content}</div> : <label className="field">{content}</label>;
}

export function Tabs<T extends string>({tabs, active, onChange}: { tabs: { id: T; label: ReactNode }[]; active: T; onChange: (id: T) => void }) {
	return (
		<div className="tabs" role="tablist">
			{tabs.map(tab => (
				<button key={tab.id} type="button" role="tab" aria-selected={tab.id === active} className={`tab${tab.id === active ? ' tab-active' : ''}`} onClick={() => onChange(tab.id)}>
					{tab.label}
				</button>
			))}
		</div>
	);
}

export function errorText(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

/**
 * Button group to pick one of a few options, e.g. the theme.
 */
export function Segmented<T extends string>({options, value, onChange, label}: { options: { id: T; label: ReactNode; icon?: IconName }[]; value: T; onChange: (id: T) => void; label: string }) {
	return (
		<div className="segmented" role="radiogroup" aria-label={label}>
			{options.map(option => (
				<button key={option.id} type="button" role="radio" aria-checked={option.id === value} className={`segment${option.id === value ? ' segment-active' : ''}`} onClick={() => onChange(option.id)}>
					{option.icon && <Icon name={option.icon} size={15}/>}
					<span>{option.label}</span>
				</button>
			))}
		</div>
	);
}

export interface MenuItem {
	label: string;
	icon?: IconName;
	onSelect: () => void;
	danger?: boolean;
	disabled?: boolean;
	hidden?: boolean;
}

/**
 * Popup menu of a trigger button, positioned fixed so it is not clipped by scrolling table containers.
 */
export function Menu({items, trigger, label = 'More actions', align = 'end', header}: { items: MenuItem[]; trigger?: ReactNode; label?: string; align?: 'start' | 'end'; header?: ReactNode }) {
	const [open, setOpen] = useState(false);
	const [position, setPosition] = useState<{ top: number; left?: number; right?: number }>({top: 0});
	const button = useRef<HTMLButtonElement>(null);
	const popup = useRef<HTMLDivElement>(null);
	const visible = items.filter(item => !item.hidden);
	
	useLayoutEffect(() => {
		if (!open || !button.current || !popup.current) {
			return;
		}
		const rect = button.current.getBoundingClientRect();
		const height = popup.current.offsetHeight;
		const below = rect.bottom + 4 + height <= window.innerHeight;
		setPosition({
			top: below ? rect.bottom + 4 : Math.max(8, rect.top - 4 - height),
			...(align === 'end' ? {right: window.innerWidth - rect.right} : {left: rect.left}),
		});
	}, [open, align]);
	
	useEffect(() => {
		if (!open) {
			return;
		}
		const close = (event: Event) => {
			if (event.type === 'keydown' && (event as KeyboardEvent).key !== 'Escape') {
				return;
			}
			if (event.type === 'mousedown' && (popup.current?.contains(event.target as Node) || button.current?.contains(event.target as Node))) {
				return;
			}
			setOpen(false);
		};
		document.addEventListener('mousedown', close);
		document.addEventListener('keydown', close);
		window.addEventListener('resize', close);
		window.addEventListener('scroll', close, true);
		return () => {
			document.removeEventListener('mousedown', close);
			document.removeEventListener('keydown', close);
			window.removeEventListener('resize', close);
			window.removeEventListener('scroll', close, true);
		};
	}, [open]);
	
	if (visible.length === 0) {
		return null;
	}
	return (
		<>
			<button ref={button} type="button" className={trigger ? 'menu-trigger' : 'btn btn-ghost btn-small btn-icon'} aria-haspopup="menu" aria-expanded={open} aria-label={label} title={trigger ? undefined : label}
					onClick={event => {
						event.stopPropagation();
						setOpen(value => !value);
					}}>
				{trigger ?? <Icon name="more" size={16}/>}
			</button>
			{open && (
				<div ref={popup} className="menu" role="menu" style={position} onClick={event => event.stopPropagation()}>
					{header && <div className="menu-header">{header}</div>}
					{visible.map(item => (
						<button key={item.label} type="button" role="menuitem" className={`menu-item${item.danger ? ' menu-item-danger' : ''}`} disabled={item.disabled} onClick={() => {
							setOpen(false);
							item.onSelect();
						}}>
							{item.icon && <Icon name={item.icon} size={16}/>}
							<span>{item.label}</span>
						</button>
					))}
				</div>
			)}
		</>
	);
}

export function SearchField({value, onChange, placeholder}: { value: string; onChange: (value: string) => void; placeholder: string }) {
	return (
		<div className="search-field">
			<Icon name="search" size={16}/>
			<input type="search" placeholder={placeholder} aria-label={placeholder} value={value} onChange={event => onChange(event.target.value)}/>
		</div>
	);
}
