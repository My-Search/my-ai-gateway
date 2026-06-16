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