// Public subset used by our reader. page-flip 2.0.7 ships JS without .d.ts files.
declare module 'page-flip/dist/js/page-flip.module.js' {
  export class PageFlip {
    constructor(element: HTMLElement, settings: {
      width: number; height: number; size: 'stretch' | 'fixed';
      minWidth: number; maxWidth: number; minHeight: number; maxHeight: number;
      startPage: number; autoSize: boolean; usePortrait: boolean; showCover: boolean;
      drawShadow: boolean; maxShadowOpacity: number; flippingTime: number;
      mobileScrollSupport: boolean; useMouseEvents: boolean; showPageCorners: boolean;
    });
    on(name: string, callback: (event: { data: unknown; object: PageFlip }) => void): this;
    off(name: string): void;
    loadFromHTML(items: HTMLElement[]): void;
    updateFromHtml(items: HTMLElement[]): void;
    update(): void;
    destroy(): void;
    getCurrentPageIndex(): number;
    getOrientation(): 'portrait' | 'landscape';
    getState(): string;
    getRender(): { finishAnimation(): void };
    getSettings(): { flippingTime: number; showPageCorners: boolean };
    turnToPage(index: number): void;
    turnToNextPage(): void;
    turnToPrevPage(): void;
    flip(index: number, corner?: 'top' | 'bottom'): void;
    flipNext(corner?: 'top' | 'bottom'): void;
    flipPrev(corner?: 'top' | 'bottom'): void;
  }
}
