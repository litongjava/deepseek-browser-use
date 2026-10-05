import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from dsb import Printer, Response, build_parser, cmd_health, cmd_start, cmd_state


class CaptureOutputTest(unittest.TestCase):
    def response(self, ok=True):
        return Response('test', 200, {'ok': ok, 'data': {
            'text': '页面正文', 'capture_degraded': True,
            'capture_note': '截图不可用，联系 test@example.com',
            'observationComplete': False, 'indicesUsable': False,
            'browser': {'engine': 'chromium', 'profileDir': 'test'},
        }}, '', 1)

    def test_text_only_retains_warnings_on_stderr_and_redacts_them(self):
        args = build_parser().parse_args(['state', '--text-only'])
        client = Mock(); client.command.return_value = self.response()
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(cmd_state(client, args, Printer(out=stdout)), 0)
        self.assertEqual(stdout.getvalue().strip(), '页面正文')
        self.assertIn('截图取证不完整', stderr.getvalue())
        self.assertIn('observationComplete=false', stderr.getvalue())
        self.assertIn('indicesUsable=false', stderr.getvalue())
        self.assertNotIn('test@example.com', stderr.getvalue())

    def test_start_health_state_accept_out_and_preserve_failure_exit(self):
        with tempfile.TemporaryDirectory() as folder:
            for name, handler in [('start', cmd_start), ('health', cmd_health), ('state', cmd_state)]:
                with self.subTest(name=name):
                    target = Path(folder) / f'{name}.json'
                    args = build_parser().parse_args([name, '--out', str(target)])
                    client = Mock()
                    client.start.return_value = client.health.return_value = client.command.return_value = self.response(False)
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(handler(client, args, Printer(out=io.StringIO())), 2)
                    data = json.loads(target.read_text(encoding='utf-8'))
                    self.assertFalse(data['ok'])
                    self.assertTrue(data['data']['capture_degraded'])
                    self.assertNotIn('test@example.com', target.read_text(encoding='utf-8'))

    def test_start_selection_has_no_extra_profile_lines(self):
        args = build_parser().parse_args(['start', '--select', 'data.browser.engine'])
        client = Mock(); client.start.return_value = self.response()
        output = io.StringIO()
        self.assertEqual(cmd_start(client, args, Printer(out=output, select=args.select)), 0)
        self.assertEqual(json.loads(output.getvalue()), 'chromium')

    def test_state_projection_keeps_warning_out_of_json(self):
        args = build_parser().parse_args(['state', '--select', 'data.text'])
        client = Mock(); client.command.return_value = self.response()
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(cmd_state(client, args, Printer(out=stdout, select=args.select)), 0)
        self.assertEqual(json.loads(stdout.getvalue()), '页面正文')
        self.assertIn('截图取证不完整', stderr.getvalue())

    def test_start_file_output_does_not_hide_engine_warning(self):
        with tempfile.TemporaryDirectory() as folder:
            args = build_parser().parse_args(['start', '--out', str(Path(folder) / 'start.json')])
            client = Mock()
            client.start.return_value = Response('test', 200, {'ok': True, 'data': {'engineHonored': False}}, '', 1)
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                self.assertEqual(cmd_start(client, args, Printer(out=io.StringIO())), 0)
            self.assertIn('engineHonored=false', stderr.getvalue())


if __name__ == '__main__':
    unittest.main()
