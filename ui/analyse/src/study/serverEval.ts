import { prop } from 'common';
import { bind, onInsert } from 'common/snabbdom';
import { spinnerVdom } from 'common/spinner';
import type { PlyChartHTMLElement } from 'chart/dist/interface';
import { h, VNode } from 'snabbdom';
import AnalyseCtrl from '../ctrl';

export default class ServerEval {
  requested = prop(false);
  chartEl = prop<PlyChartHTMLElement | null>(null);

  constructor(readonly root: AnalyseCtrl, readonly chapterId: () => string) {}

  reset = () => {
    this.requested(false);
  };

  onMergeAnalysisData = () => window.LichessChartGame?.acpl.update?.(this.root.data, this.root.mainline);

  request = () => {
    this.root.socket.send('requestAnalysis', this.chapterId());
    this.requested(true);
  };
}

export function view(ctrl: ServerEval): VNode {
  const analysis = ctrl.root.data.analysis;

  if (!ctrl.root.showComputer()) return disabled();
  if (!analysis) return ctrl.requested() ? requested() : requestButton(ctrl);

  return h(
    'div.study__server-eval.ready.' + analysis.id,
    {
      hook: onInsert(el => {
        lichess.requestIdleCallback(async () => {
          await lichess.loadModule('chart.game');
          window.LichessChartGame!.acpl(ctrl.root.data, ctrl.root.mainline, ctrl.root.trans, el, false);
          ctrl.chartEl(el as PlyChartHTMLElement);
        }, 800);
      }),
    },
    [h('div.study__message', spinnerVdom())]
  );
}

const disabled = () => h('div.study__server-eval.disabled.padded', 'You disabled computer analysis.');

const requested = () => h('div.study__server-eval.requested.padded', spinnerVdom());

function requestButton(ctrl: ServerEval) {
  const root = ctrl.root,
    noarg = root.trans.noarg;
  return h(
    'div.study__message',
    root.mainline.length < 5
      ? h('p', noarg('theChapterIsTooShortToBeAnalysed'))
      : !root.study!.members.canContribute()
      ? [noarg('onlyContributorsCanRequestAnalysis')]
      : [
          h('p', [noarg('getAFullComputerAnalysis'), h('br'), noarg('makeSureTheChapterIsComplete')]),
          h(
            'a.button.text',
            {
              attrs: {
                'data-icon': '',
                disabled: root.mainline.length < 5,
              },
              hook: bind('click', ctrl.request, root.redraw),
            },
            noarg('requestAComputerAnalysis')
          ),
        ]
  );
}
