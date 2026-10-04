//! Validated container-relative grid plans, independent of a UI library.
//! Wire form: `minimum_width,columns,column_gap,row_gap;...`.

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GridBreakpoint {
    pub minimum_width: f32,
    pub columns: usize,
    pub column_gap: f32,
    pub row_gap: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridTemplate {
    levels: Vec<GridBreakpoint>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GridTemplateError {
    InvalidShape,
    InvalidNumber,
    InvalidColumns,
    InvalidThresholds,
}

impl GridTemplate {
    pub fn parse(wire: &str) -> Result<Self, GridTemplateError> {
        // Bound parsing work before allocating, including adversarial wire data.
        if wire.is_empty() || wire.len() > 1024 {
            return Err(GridTemplateError::InvalidShape);
        }
        let mut levels = Vec::with_capacity(6);
        for row in wire.split(';') {
            if levels.len() == 6 {
                return Err(GridTemplateError::InvalidShape);
            }
            let mut fields = row.split(',');
            let minimum_width = parse_dimension(fields.next())?;
            let columns = fields
                .next()
                .ok_or(GridTemplateError::InvalidShape)?
                .parse::<usize>()
                .map_err(|_| GridTemplateError::InvalidColumns)?;
            if !(1..=64).contains(&columns) {
                return Err(GridTemplateError::InvalidColumns);
            }
            let column_gap = parse_dimension(fields.next())?;
            let row_gap = parse_dimension(fields.next())?;
            if fields.next().is_some() {
                return Err(GridTemplateError::InvalidShape);
            }
            if levels
                .last()
                .map_or(minimum_width != 0.0, |previous: &GridBreakpoint| {
                    minimum_width <= previous.minimum_width
                })
            {
                return Err(GridTemplateError::InvalidThresholds);
            }
            levels.push(GridBreakpoint {
                minimum_width,
                columns,
                column_gap,
                row_gap,
            });
        }
        Ok(Self { levels })
    }

    pub fn resolve(&self, width: f32) -> Result<(usize, GridBreakpoint), GridTemplateError> {
        if !width.is_finite() || width < 0.0 {
            return Err(GridTemplateError::InvalidNumber);
        }
        // Construction guarantees a base level at zero and ordered thresholds.
        let index = self
            .levels
            .partition_point(|level| level.minimum_width <= width)
            - 1;
        Ok((index, self.levels[index]))
    }
}

fn parse_dimension(value: Option<&str>) -> Result<f32, GridTemplateError> {
    let parsed = value
        .ok_or(GridTemplateError::InvalidShape)?
        .parse::<f32>()
        .map_err(|_| GridTemplateError::InvalidNumber)?;
    if !parsed.is_finite() || parsed < 0.0 {
        return Err(GridTemplateError::InvalidNumber);
    }
    Ok(parsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_all_six_ui_thresholds_and_independent_gutters() {
        let template = GridTemplate::parse(
            "0,1,8,4;640,2,12,8;768,3,16,12;1024,4,20,16;1280,5,24,20;1536,6,28,24",
        )
        .unwrap();
        for (index, width) in [0.0, 640.0, 768.0, 1024.0, 1280.0, 1536.0]
            .into_iter()
            .enumerate()
        {
            let (level, plan) = template.resolve(width).unwrap();
            assert_eq!(level, index);
            assert_eq!(plan.columns, index + 1);
            assert_eq!(plan.column_gap, 8.0 + index as f32 * 4.0);
            assert_eq!(plan.row_gap, 4.0 + index as f32 * 4.0);
            if index > 0 {
                assert_eq!(template.resolve(width - 0.25).unwrap().0, index - 1);
            }
        }
        assert_eq!(template.resolve(4096.0).unwrap().0, 5);
    }

    #[test]
    fn supports_native_thresholds_without_reinterpreting_them() {
        let template =
            GridTemplate::parse("0,12,12,8;600,12,16,8;840,12,24,12;1200,12,24,16;1600,12,32,16")
                .unwrap();
        assert_eq!(template.resolve(768.0).unwrap().0, 1);
        assert_eq!(template.resolve(840.0).unwrap().0, 2);
    }

    #[test]
    fn rejects_invalid_or_unbounded_plans() {
        for wire in [
            "",
            "0,1,0",
            "0,1,0,0,0",
            "1,1,0,0",
            "0,1,0,0;0,2,0,0",
            "0,0,0,0",
            "0,65,0,0",
            "0,1,-1,0",
            "0,1,NaN,0",
            "0,1,inf,0",
            "0,1,0,0;600,2,0,0;500,3,0,0",
            "0,1,0,0;",
            "NaN,1,0,0",
        ] {
            assert!(GridTemplate::parse(wire).is_err(), "accepted {wire}");
        }
        assert!(GridTemplate::parse(&"0".repeat(1025)).is_err());
        assert!(
            GridTemplate::parse("0,1,0,0;1,1,0,0;2,1,0,0;3,1,0,0;4,1,0,0;5,1,0,0;6,1,0,0").is_err()
        );
        let template = GridTemplate::parse("0,1,0,0").unwrap();
        for width in [-1.0, f32::NAN, f32::INFINITY] {
            assert!(template.resolve(width).is_err());
        }
    }
}
