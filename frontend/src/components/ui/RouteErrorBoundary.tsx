import React from 'react';
import { AlertTriangle, RotateCcw } from 'lucide-react';
import { Button, Card, CardBody } from './primitives';

interface Props {
  children: React.ReactNode;
  resetKey?: string;
}

interface State {
  error: Error | null;
}

export class RouteErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidUpdate(prevProps: Props) {
    if (this.state.error && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  render() {
    if (this.state.error) {
      return (
        <div className="py-10 max-w-2xl mx-auto">
          <Card className="border-rose-200 dark:border-rose-900/60">
            <CardBody className="space-y-3">
              <div className="flex items-center gap-2.5 text-rose-600 dark:text-rose-400 font-semibold">
                <AlertTriangle className="h-5 w-5 shrink-0" />
                <span>Ошибка отображения страницы</span>
              </div>
              <p className="text-xs text-slate-600 dark:text-slate-300">
                Произошла непредвиденная ошибка интерфейса. Вы можете обновить блок или перезагрузить страницу.
              </p>
              <pre className="rounded-lg bg-slate-950 text-rose-300 p-3 text-xs font-mono overflow-auto max-h-48">
                {this.state.error.message || String(this.state.error)}
              </pre>
              <div className="flex items-center gap-2">
                <Button size="sm" onClick={() => this.setState({ error: null })}>
                  <RotateCcw className="h-3.5 w-3.5" />
                  Попробовать снова
                </Button>
                <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
                  Перезагрузить окно
                </Button>
              </div>
            </CardBody>
          </Card>
        </div>
      );
    }
    return this.props.children;
  }
}
