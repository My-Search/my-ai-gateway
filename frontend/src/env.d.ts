/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// SVG ICONS virtual module declaration
declare module 'virtual:svg-icons-register' {
  const content: any
  export default content
}

// Fix for vite-plugin-svg-icons
interface SvgIconElement extends SVGElement {
  href: SVGAnimatedString
}

// sortablejs ships no types; only the small API surface we use is declared here.
declare module 'sortablejs' {
  interface SortableOptions {
    handle?: string
    animation?: number
    easing?: string
    ghostClass?: string
    dragClass?: string
    forceFallback?: boolean
    fallbackClass?: string
    fallbackOnBody?: boolean
    fallbackTolerance?: number
    delay?: number
    delayOnTouchOnly?: boolean
    onEnd?: (evt: { oldIndex?: number; newIndex?: number }) => void
  }
  export default class Sortable {
    constructor(el: HTMLElement, options?: SortableOptions)
    destroy(): void
  }
}
