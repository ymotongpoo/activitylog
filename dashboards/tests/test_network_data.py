"""Run with PROMTOOL=/path/to/promtool python3 -m unittest discover -s dashboards/tests."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DASHBOARDS = Path(__file__).resolve().parents[1]
PROMTOOL = os.environ.get('PROMTOOL') or shutil.which('promtool')
TRANSPORTS = [('mobile', 'Mobile data', 'Top apps by mobile data', 1),
              ('wifi', 'Wi-Fi data', 'Top apps by Wi-Fi data', 10)]


def panels(filename):
    return json.loads((DASHBOARDS / filename).read_text())['panels']


class NetworkDataDashboardTest(unittest.TestCase):
    def test_android_layout(self):
        items = panels('android.json')
        self.assertEqual(len(items), len({p['id'] for p in items}))
        for _, total_title, ranking_title, _ in TRANSPORTS:
            total = next(p for p in items if p['title'] == total_title and p['type'] == 'stat')
            ranking = next(p for p in items if p['title'] == ranking_title)
            self.assertEqual(1, total['gridPos']['y'])
            self.assertGreater(ranking['gridPos']['y'], total['gridPos']['y'])
            self.assertEqual('bargauge', ranking['type'])
            self.assertEqual('horizontal', ranking['options']['orientation'])
        for i, p in enumerate(items):
            a = p['gridPos']
            self.assertLessEqual(a['x'] + a['w'], 24)
            for q in items[i + 1:]:
                b = q['gridPos']
                overlap = (a['x'] < b['x'] + b['w'] and b['x'] < a['x'] + a['w']
                           and a['y'] < b['y'] + b['h'] and b['y'] < a['y'] + a['h'])
                self.assertFalse(overlap, (p['title'], q['title']))

    @unittest.skipUnless(PROMTOOL, 'set PROMTOOL to run PromQL regression cases')
    def test_saved_queries_with_sparse_samples_and_selected_ranges(self):
        series = []
        common = 'job="activitylog/activitylog-android",instance="phone-a"'
        for transport, _, _, scale in TRANSPORTS:
            metric = f'activity_network_{transport}_usage_bytes_total'
            for attrs, start, increment in [
                ('activity_network_scope="device"', 1000, 10),
                ('activity_network_scope="app",activity_app_id="a",activity_app_name="App A",process_uid="42"', 500, 6),
                ('activity_network_scope="app",activity_app_id="b",activity_app_name="App B",process_uid="43"', 500, 4),
                ('activity_network_scope="app",activity_app_id="idle",activity_app_name="Idle",process_uid="44"', 100, 0),
            ]:
                series.append({'series': f'{metric}{{{common},{attrs}}}', 'values': f'{start}+{increment * scale}x192'})
            series.append({'series': f'{metric}{{job="activitylog/activitylog-android",instance="phone-b",activity_network_scope="device"}}', 'values': f'10000+{100 * scale}x192'})
            series.append({'series': f'{metric}{{job="other",instance="phone-a",activity_network_scope="device"}}', 'values': '0+100000x192'})
        checks, single_checks = [], []
        for _, total_title, ranking_title, scale in TRANSPORTS:
            for filename in ('android.json', 'activitylog.json'):
                items = panels(filename)
                total = next(p for p in items if p['title'] == total_title and p['type'] == 'stat')
                ranking = next(p for p in items if p['title'] == ranking_title)
                def query(panel, duration='24h', device='phone-a'):
                    return (panel['targets'][0]['expr'].replace('$__range', duration)
                            .replace('${instance:pipe}', device).replace('$device', device))
                # Two-day source history: selection must return 24h / 1h increments,
                # not the latest absolute total. The last sample is seven minutes old.
                for duration, device, expected in [('24h', 'phone-a', 960), ('1h', 'phone-a', 40), ('24h', '.*', 10560)]:
                    checks.append({'expr': query(total, duration, device), 'eval_time': '48h7m',
                                   'exp_samples': [{'labels': '{}', 'value': expected * scale}]})
                labels = 'instance="phone-a",' if filename == 'activitylog.json' else ''
                checks.append({'expr': query(ranking), 'eval_time': '48h7m', 'exp_samples': [
                    {'labels': '{' + labels + 'activity_app_id="a",activity_app_name="App A"}', 'value': 576 * scale},
                    {'labels': '{' + labels + 'activity_app_id="b",activity_app_name="App B"}', 'value': 384 * scale},
                ]})
                checks.append({'expr': query(total, device='absent'), 'eval_time': '48h7m', 'exp_samples': []})
                single_checks.append({'expr': query(total, duration='1h'), 'eval_time': '15m', 'exp_samples': []})
        fixture = {'evaluation_interval': '1m', 'tests': [
            {'interval': '15m', 'input_series': series, 'promql_expr_test': checks},
            {'interval': '15m', 'input_series': [{'series': f'activity_network_{transport}_usage_bytes_total{{{common},activity_network_scope="device"}}', 'values': '_ 42'} for transport, _, _, _ in TRANSPORTS],
             'promql_expr_test': single_checks},
        ]}
        with tempfile.TemporaryDirectory(prefix='activitylog-promql-') as tmp:
            path = Path(tmp) / 'network-data.yml'
            path.write_text(json.dumps(fixture))  # JSON is also valid YAML.
            result = subprocess.run([PROMTOOL, 'test', 'rules', str(path)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
