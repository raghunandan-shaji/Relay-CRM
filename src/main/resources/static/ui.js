export const pretty = value => String(value ?? '').replaceAll('_', ' ').toLowerCase().replace(/^./, char => char.toUpperCase());
export const date = value => value ? new Intl.DateTimeFormat(undefined, {dateStyle:'medium', timeStyle:'short'}).format(new Date(value)) : '—';
export function claims(token) {
  const part = token.split('.')[1];
  if (!part) throw new Error('Invalid session response');
  const bytes = Uint8Array.from(atob(part.replaceAll('-', '+').replaceAll('_', '/')), char => char.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}
export function forwardStages(current, stages) {
  const index = stages.indexOf(current);
  return index < 0 ? [] : stages.slice(index + 1);
}
export function element(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else if (key === 'class') node.className = value;
    else if (value !== undefined && value !== null && value !== false) node.setAttribute(key, value === true ? '' : value);
  }
  node.append(...children.flat().filter(value => value !== undefined && value !== null));
  return node;
}
