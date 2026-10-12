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
METRIC = 'activity_network_mobile_usage_bytes_total'


def panels(filename):
    return json.loads((DASHBOARDS / filename).read_text())['panels']


class MobileDataDashboardTest(unittest.TestCase):
    def test_android_layout(self):
        items = panels('android.json')
        self.assertEqual(len(items), len({p['id'] for p in items}))
        total = next(p for p in items if p['title'] == 'Mobile data' and p['type'] == 'stat')
        ranking = next(p for p in items if p['title'] == 'Top apps by mobile data')
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
        for attrs, values in [
            ('activity_network_scope="device"', '1000+10x192'),
            ('activity_network_scope="app",activity_app_id="a",activity_app_name="App A",process_uid="42"', '500+6x192'),
            ('activity_network_scope="app",activity_app_id="b",activity_app_name="App B",process_uid="43"', '500+4x192'),
            ('activity_network_scope="app",activity_app_id="idle",activity_app_name="Idle",process_uid="44"', '100+0x192'),
        ]:
            series.append({'series': f'{METRIC}{{{common},{attrs}}}', 'values': values})
        series.append({'series': f'{METRIC}{{job="activitylog/activitylog-android",instance="phone-b",activity_network_scope="device"}}', 'values': '10000+100x192'})
        series.append({'series': f'{METRIC}{{job="other",instance="phone-a",activity_network_scope="device"}}', 'values': '0+100000x192'})
        checks, single_checks = [], []
        for filename in ('android.json', 'activitylog.json'):
            items = panels(filename)
            total = next(p for p in items if p['title'] == 'Mobile data' and p['type'] == 'stat')
            ranking = next(p for p in items if p['title'] == 'Top apps by mobile data')
            def query(panel, duration='24h', device='phone-a'):
                return (panel['targets'][0]['expr'].replace('$__range', duration)
                        .replace('${instance:pipe}', device).replace('$device', device))
            # Two-day source history: selection must return 24h / 1h increments,
            # not the latest absolute total. The last sample is seven minutes old.
            for duration, device, expected in [('24h', 'phone-a', 960), ('1h', 'phone-a', 40), ('24h', '.*', 10560)]:
                checks.append({'expr': query(total, duration, device), 'eval_time': '48h7m',
                               'exp_samples': [{'labels': '{}', 'value': expected}]})
            labels = 'instance="phone-a",' if filename == 'activitylog.json' else ''
            checks.append({'expr': query(ranking), 'eval_time': '48h7m', 'exp_samples': [
                {'labels': '{' + labels + 'activity_app_id="a",activity_app_name="App A"}', 'value': 576},
                {'labels': '{' + labels + 'activity_app_id="b",activity_app_name="App B"}', 'value': 384},
            ]})
            checks.append({'expr': query(total, device='absent'), 'eval_time': '48h7m', 'exp_samples': []})
            single_checks.append({'expr': query(total, duration='1h'), 'eval_time': '15m', 'exp_samples': []})
        fixture = {'evaluation_interval': '1m', 'tests': [
            {'interval': '15m', 'input_series': series, 'promql_expr_test': checks},
            {'interval': '15m', 'input_series': [{'series': f'{METRIC}{{{common},activity_network_scope="device"}}', 'values': '_ 42'}],
             'promql_expr_test': single_checks},
        ]}
        with tempfile.TemporaryDirectory(prefix='activitylog-promql-') as tmp:
            path = Path(tmp) / 'mobile-data.yml'
            path.write_text(json.dumps(fixture))  # JSON is also valid YAML.
            result = subprocess.run([PROMTOOL, 'test', 'rules', str(path)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
