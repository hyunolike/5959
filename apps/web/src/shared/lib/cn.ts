import { type ClassValue, clsx } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/** globals.css의 글자 단계, 모서리, 그림자 이름. tailwind-merge가 글자 색이나 다른 유틸리티와 헷갈리지 않게 알려 준다. */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      "font-size": [
        {
          text: [
            "caption",
            "label",
            "body-sm",
            "body",
            "body-lg",
            "title",
            "heading",
            "display",
            "numeric",
          ],
        },
      ],
      rounded: [{ rounded: ["inline", "control-sm", "control", "surface"] }],
      shadow: [{ shadow: ["game", "float", "raised"] }],
    },
  },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
